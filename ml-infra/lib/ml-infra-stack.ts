import * as cdk from 'aws-cdk-lib';
import * as ec2 from 'aws-cdk-lib/aws-ec2';
import * as ecs from 'aws-cdk-lib/aws-ecs';
import * as ecr from 'aws-cdk-lib/aws-ecr';
import * as elbv2 from 'aws-cdk-lib/aws-elasticloadbalancingv2';
import * as cloudwatch from 'aws-cdk-lib/aws-cloudwatch';
import * as lambda from 'aws-cdk-lib/aws-lambda';
import * as events from 'aws-cdk-lib/aws-events';
import * as targets from 'aws-cdk-lib/aws-events-targets';
import * as iam from 'aws-cdk-lib/aws-iam';
import * as appscaling from 'aws-cdk-lib/aws-applicationautoscaling';

export class MlInfraStack extends cdk.Stack {
    constructor(scope: cdk.App, id: string, props?: cdk.StackProps) {
        super(scope, id, props);

        // ========================
        // Parameters
        // ========================
        const valkeyHost = new cdk.CfnParameter(this, 'ValkeyHost', {
            type: 'String',
            description: 'Elastic IP of your EC2 running compose (Valkey, Restate, Postgres)',
        });

        const valkeyPassword = new cdk.CfnParameter(this, 'ValkeyPassword', {
            type: 'String',
            description: 'Password for Valkey authentication',
            noEcho: true,
        });

        // ========================
        // Networking
        // ========================
        const vpc = ec2.Vpc.fromLookup(this, 'Vpc', {isDefault: true});

        const mlSg = new ec2.SecurityGroup(this, 'MlWorkerSg', {
            vpc,
            description: 'ML Fargate workers',
            allowAllOutbound: true,
        });

        // ========================
        // ECR Repository
        // ========================
        const repo = new ecr.Repository(this, 'MlRepo', {
            repositoryName: 'ml-worker',
            removalPolicy: cdk.RemovalPolicy.DESTROY,
            emptyOnDelete: true,
            lifecycleRules: [{maxImageCount: 5}],
        });

        // ========================
        // ECS Cluster + Task Definition
        // ========================
        const cluster = new ecs.Cluster(this, 'MlCluster', {vpc});

        const taskDef = new ecs.FargateTaskDefinition(this, 'MlTask', {
            cpu: 8192,
            memoryLimitMiB: 16384,
        });

        taskDef.addContainer('ml', {
            image: ecs.ContainerImage.fromEcrRepository(repo, 'latest'),
            portMappings: [{containerPort: 8000}],
            environment: {
                DATABASE_HOST: valkeyHost.valueAsString,
                DATABASE_PORT: '5444',
                DATABASE_PASSWORD: 'mypassword',
                REDIS_URL: cdk.Fn.join('', ['redis://:', valkeyPassword.valueAsString, '@', valkeyHost.valueAsString, ':6379']),
                REDIS_PASSWORD: valkeyPassword.valueAsString,
            },
            logging: ecs.LogDrivers.awsLogs({streamPrefix: 'ml-worker'}),
        });

        // ========================
        // ALB
        // ========================
        const alb = new elbv2.ApplicationLoadBalancer(this, 'MlAlb', {
            vpc,
            internetFacing: true,
            idleTimeout: cdk.Duration.seconds(4000)
        });

        const listener = alb.addListener('Http', {port: 80});

        // ========================
        // ECS Service (scale-to-zero)
        // ========================
        const service = new ecs.FargateService(this, 'MlService', {
            cluster,
            taskDefinition: taskDef,
            desiredCount: 0,          // start empty, autoscaling takes over
            assignPublicIp: true,
            securityGroups: [mlSg],
            minHealthyPercent: 100,
            maxHealthyPercent: 200,
        });

        listener.addTargets('MlTargets', {
            port: 8000,
            targets: [service],
            healthCheck: {
                path: '/health',
                interval: cdk.Duration.seconds(30),
                healthyThresholdCount: 2,
            },
            deregistrationDelay: cdk.Duration.seconds(300),
        });

        // ========================
        // Autoscaling
        // ========================
        const scaling = service.autoScaleTaskCount({
            minCapacity: 0,
            maxCapacity: 5,
        });

        scaling.scaleOnMetric('ScaleUp', {
            metric: new cloudwatch.Metric({
                namespace: 'Custom/ML',
                metricName: 'MlWorkerDemand',
                statistic: 'Average',
                period: cdk.Duration.seconds(60),
            }),
            scalingSteps: [
                {lower: 2, change: +1},
                {lower: 4, change: +2},
            ],
            cooldown: cdk.Duration.seconds(60),
            adjustmentType: appscaling.AdjustmentType.CHANGE_IN_CAPACITY,
        });

        const scaleDownPolicy = new appscaling.StepScalingAction(this, 'ScaleDownAction', {
            scalingTarget: scaling,
            adjustmentType: appscaling.AdjustmentType.CHANGE_IN_CAPACITY,
            cooldown: cdk.Duration.seconds(300),
        });
        scaleDownPolicy.addAdjustment({adjustment: -1, upperBound: 0});

        const scaleDownAlarm = new cloudwatch.Alarm(this, 'ScaleDownAlarm', {
            metric: new cloudwatch.Metric({
                namespace: 'Custom/ML',
                metricName: 'MlWorkerDemand',
                statistic: 'Average',
                period: cdk.Duration.minutes(1),
            }),
            threshold: 0.05,
            comparisonOperator: cloudwatch.ComparisonOperator.LESS_THAN_THRESHOLD,
            evaluationPeriods: 15,
            datapointsToAlarm: 15,
            treatMissingData: cloudwatch.TreatMissingData.BREACHING,
        });

        scaleDownAlarm.addAlarmAction(new cdk.aws_cloudwatch_actions.ApplicationScalingAction(scaleDownPolicy));

        // ========================
        // Metric Publisher Lambda
        // Checks both Valkey backpressure AND recent ALB HTTP traffic.
        // Publishes a single "demand" metric that autoscaling reacts to.
        // ========================

        const metricFn = new lambda.Function(this, 'MetricPublisher', {
            runtime: lambda.Runtime.PYTHON_3_12,
            handler: 'index.handler',
            timeout: cdk.Duration.seconds(15),
            environment: {
                VALKEY_HOST: valkeyHost.valueAsString,
                VALKEY_PORT: '6379',
                VALKEY_PASSWORD: valkeyPassword.valueAsString,
                ECS_CLUSTER: cluster.clusterName,
                ECS_SERVICE: service.serviceName,
                ALB_ARN_SUFFIX: alb.loadBalancerFullName,
                STREAM_GROUPS: JSON.stringify([
                    ["ml_training:training_requests", "training_workers"],
                    ["ml_training:tuning_requests", "tuning_workers"],
                    ["ml_training:stability_selection_requests", "analysis_workers"]
                ]),
            },
            code: lambda.Code.fromInline(`
import boto3, os, socket, json
from datetime import datetime, timedelta, timezone

cw = boto3.client('cloudwatch')

ecs_client = boto3.client('ecs')

def handler(event, context):
    pending = get_total_pending()
    recent_http = get_recent_request_count()
    ecs_pending = get_pending_tasks()

    if pending > 0:
        value = float(pending)
    elif ecs_pending > 0:
        value = 1.0   # tasks still spinning up, don't interfere
    elif recent_http > 0:
        value = 1.0
    else:
        value = 0.0

    cw.put_metric_data(
        Namespace='Custom/ML',
        MetricData=[{
            'MetricName': 'MlWorkerDemand',
            'Value': value,
            'Unit': 'Count',
        }]
    )
    print(f"pending={pending} http={recent_http} ecs_pending={ecs_pending} published={value}")
    return {'pending': pending, 'recent_http': recent_http, 'pending': ecs_pending, 'published': value}


def get_pending_tasks():
    try:
        resp = ecs_client.describe_services(
            cluster=os.environ['ECS_CLUSTER'],
            services=[os.environ['ECS_SERVICE']]
        )
        svc = resp['services'][0]
        return svc['pendingCount'] + max(0, svc['desiredCount'] - svc['runningCount'])
    except Exception as e:
        print(f"ECS query error: {e}")
    return 0

def get_total_pending():
    stream_groups = json.loads(os.environ['STREAM_GROUPS'])
    total = 0
    for stream, group in stream_groups:
        total += get_valkey_pending(stream, group)
    return total


def _resp_cmd(*args):
    """Build a RESP protocol command."""
    parts = [f"*{len(args)}\\r\\n"]
    for a in args:
        a = str(a)
        parts.append(f"\${len(a)}\\r\\n{a}\\r\\n")
    return "".join(parts)


def _valkey_connect():
    """Open socket and authenticate."""
    host = os.environ['VALKEY_HOST']
    port = int(os.environ['VALKEY_PORT'])
    password = os.environ.get('VALKEY_PASSWORD', '')
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.settimeout(5)
    sock.connect((host, port))
    if password:
        sock.sendall(_resp_cmd('AUTH', password).encode())
        auth_resp = sock.recv(4096).decode()
        if not auth_resp.startswith('+OK'):
            sock.close()
            raise RuntimeError(f"Valkey AUTH failed: {auth_resp.strip()}")
    return sock


def get_valkey_pending(stream, group):
    try:
        sock = _valkey_connect()
        sock.sendall(_resp_cmd('XPENDING', stream, group).encode())
        resp = sock.recv(4096).decode()
        sock.close()
        for line in resp.split('\\r\\n'):
            if line.startswith(':'):
                return int(line[1:])
    except Exception as e:
        print(f"Valkey error ({stream}/{group}): {e}")
    return 0


def get_recent_request_count():
    try:
        resp = cw.get_metric_statistics(
            Namespace='AWS/ApplicationELB',
            MetricName='RequestCount',
            Dimensions=[{
                'Name': 'LoadBalancer',
                'Value': os.environ['ALB_ARN_SUFFIX']
            }],
            StartTime=datetime.now(timezone.utc) - timedelta(minutes=10),
            EndTime=datetime.now(timezone.utc),
            Period=600,
            Statistics=['Sum'],
        )
        points = resp.get('Datapoints', [])
        return int(points[0]['Sum']) if points else 0
    except Exception as e:
        print(f"CloudWatch query error: {e}")
    return 0
`),
        });

        // Lambda permissions
        metricFn.addToRolePolicy(new iam.PolicyStatement({
            actions: ['cloudwatch:PutMetricData', 'cloudwatch:GetMetricStatistics', 'ecs:DescribeServices'],
            resources: ['*'],
        }));

        // Run every minute
        new events.Rule(this, 'MetricSchedule', {
            schedule: events.Schedule.rate(cdk.Duration.minutes(1)),
            targets: [new targets.LambdaFunction(metricFn)],
        });

        // ========================
        // Outputs
        // ========================
        new cdk.CfnOutput(this, 'AlbUrl', {
            value: `http://${alb.loadBalancerDnsName}`,
            description: 'ML service endpoint (pass to Spring Boot as ml.alb-url)',
        });
        new cdk.CfnOutput(this, 'EcrRepoUri', {
            value: repo.repositoryUri,
            description: 'Docker image push target for GitHub Actions',
        });
        new cdk.CfnOutput(this, 'ClusterName', {
            value: cluster.clusterName,
            description: 'ECS cluster name (pass to Spring Boot as ml.cluster)',
        });
        new cdk.CfnOutput(this, 'ServiceName', {
            value: service.serviceName,
            description: 'ECS service name (pass to Spring Boot as ml.service)',
        });
        new cdk.CfnOutput(this, 'AlbArnSuffix', {
            value: alb.loadBalancerFullName,
            description: 'ALB ARN suffix (used by metric publisher)',
        });
    }
}