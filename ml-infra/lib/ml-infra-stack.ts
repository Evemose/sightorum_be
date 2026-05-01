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
import * as s3 from 'aws-cdk-lib/aws-s3';

export interface MlGlobalsStackProps extends cdk.StackProps {
    readonly entrypointRegion: string;
    readonly computeRegions: string[];
}

export class MlGlobalsStack extends cdk.Stack {
    public readonly modelsBucketName: string;
    public readonly imageUri: string;
    public readonly entrypointRegion: string;

    constructor(scope: cdk.App, id: string, props: MlGlobalsStackProps) {
        super(scope, id, props);

        const valkeyHost = new cdk.CfnParameter(this, 'ValkeyHost', {
            type: 'String',
            description: 'Elastic IP of your EC2 running compose (Valkey, Restate, Postgres)',
        });

        const valkeyPassword = new cdk.CfnParameter(this, 'ValkeyPassword', {
            type: 'String',
            description: 'Password for Valkey authentication',
            noEcho: true,
        });

        const vpc = ec2.Vpc.fromLookup(this, 'Vpc', {isDefault: true});

        const mlSg = new ec2.SecurityGroup(this, 'MlWorkerSg', {
            vpc,
            description: 'ML Fargate workers',
            allowAllOutbound: true,
        });

        const repo = new ecr.Repository(this, 'MlRepo', {
            repositoryName: 'ml-worker-v2',
            removalPolicy: cdk.RemovalPolicy.DESTROY,
            emptyOnDelete: true,
            lifecycleRules: [{maxImageCount: 5}],
        });

        const modelsBucket = new s3.Bucket(this, 'MlModelsBucket', {
            bucketName: 'rorm-ml-models',
            versioned: true,
            encryption: s3.BucketEncryption.S3_MANAGED,
            removalPolicy: cdk.RemovalPolicy.DESTROY,
            autoDeleteObjects: true,
            blockPublicAccess: s3.BlockPublicAccess.BLOCK_ALL,
        });

        this.modelsBucketName = modelsBucket.bucketName;
        this.imageUri = repo.repositoryUri + ':latest';
        this.entrypointRegion = props.entrypointRegion;

        const cluster = new ecs.Cluster(this, 'MlCluster', {vpc});

        const taskDef = new ecs.FargateTaskDefinition(this, 'MlTask', {
            cpu: 16384,
            memoryLimitMiB: 32768,
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
                STORAGE_BACKEND: 's3',
                STORAGE_BUCKET: modelsBucket.bucketName,
            },
            logging: ecs.LogDrivers.awsLogs({streamPrefix: 'ml-worker'}),
        });

        modelsBucket.grantReadWrite(taskDef.taskRole);

        const alb = new elbv2.ApplicationLoadBalancer(this, 'MlAlb', {
            vpc,
            internetFacing: true,
            idleTimeout: cdk.Duration.seconds(4000)
        });

        const listener = alb.addListener('Http', {port: 80});

        const service = new ecs.FargateService(this, 'MlService', {
            cluster,
            taskDefinition: taskDef,
            desiredCount: 0,
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
                dimensionsMap: {Region: props.entrypointRegion},
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
                dimensionsMap: {Region: props.entrypointRegion},
            }),
            threshold: 0.05,
            comparisonOperator: cloudwatch.ComparisonOperator.LESS_THAN_THRESHOLD,
            evaluationPeriods: 15,
            datapointsToAlarm: 15,
            treatMissingData: cloudwatch.TreatMissingData.BREACHING,
        });

        scaleDownAlarm.addAlarmAction(new cdk.aws_cloudwatch_actions.ApplicationScalingAction(scaleDownPolicy));

        const ecsServices = [
            {region: props.entrypointRegion, cluster: cluster.clusterName, service: service.serviceName},
            ...props.computeRegions.map(r => ({
                region: r,
                cluster: `MlComputeCluster-${r}`,
                service: `MlComputeService-${r}`,
            })),
        ];

        const metricFn = new lambda.Function(this, 'MetricPublisher', {
            runtime: lambda.Runtime.PYTHON_3_12,
            handler: 'index.handler',
            timeout: cdk.Duration.seconds(30),
            environment: {
                VALKEY_HOST: valkeyHost.valueAsString,
                VALKEY_PORT: '6379',
                VALKEY_PASSWORD: valkeyPassword.valueAsString,
                ENTRYPOINT_REGION: props.entrypointRegion,
                ALB_ARN_SUFFIX: alb.loadBalancerFullName,
                ECS_SERVICES: JSON.stringify(ecsServices),
                STREAM_GROUPS: JSON.stringify([
                    ["ml_training:training_requests", "training_workers"],
                    ["ml_training:tuning_requests", "tuning_workers"],
                    ["ml_training:stability_selection_requests", "analysis_workers"]
                ]),
            },
            code: lambda.Code.fromInline(`
import boto3, os, socket, json
from datetime import datetime, timedelta, timezone

cw_local = boto3.client('cloudwatch')
_ecs_clients = {}
_cw_clients = {}


def _ecs(region):
    if region not in _ecs_clients:
        _ecs_clients[region] = boto3.client('ecs', region_name=region)
    return _ecs_clients[region]


def _cw(region):
    if region not in _cw_clients:
        _cw_clients[region] = boto3.client('cloudwatch', region_name=region)
    return _cw_clients[region]


def handler(event, context):
    pending = get_total_pending()
    entrypoint_region = os.environ['ENTRYPOINT_REGION']
    recent_http = get_recent_request_count()
    services = json.loads(os.environ['ECS_SERVICES'])

    results = []
    for svc in services:
        region = svc['region']
        cluster = svc['cluster']
        service = svc['service']
        avg_cpu = get_cpu_utilization(region, cluster, service)
        cpu_norm = max(0.0, (avg_cpu - 60.0) / 20.0) if avg_cpu is not None else 0.0
        http_signal = 1.0 if (region == entrypoint_region and recent_http > 0) else 0.0
        value = max(float(pending), http_signal, cpu_norm)

        _cw(region).put_metric_data(
            Namespace='Custom/ML',
            MetricData=[{
                'MetricName': 'MlWorkerDemand',
                'Dimensions': [{'Name': 'Region', 'Value': region}],
                'Value': value,
                'Unit': 'Count',
            }]
        )
        results.append({'region': region, 'pending': pending, 'http': http_signal, 'cpu': avg_cpu, 'value': value})
        print(f"region={region} pending={pending} http={http_signal} cpu={avg_cpu} published={value}")

    return {'results': results}


def get_total_pending():
    stream_groups = json.loads(os.environ['STREAM_GROUPS'])
    total = 0
    for stream, group in stream_groups:
        total += get_valkey_pending(stream, group)
    return total


def get_cpu_utilization(region, cluster, service):
    try:
        resp = _cw(region).get_metric_statistics(
            Namespace='AWS/ECS',
            MetricName='CPUUtilization',
            Dimensions=[
                {'Name': 'ClusterName', 'Value': cluster},
                {'Name': 'ServiceName', 'Value': service},
            ],
            StartTime=datetime.now(timezone.utc) - timedelta(minutes=5),
            EndTime=datetime.now(timezone.utc),
            Period=300,
            Statistics=['Average'],
        )
        points = resp.get('Datapoints', [])
        if not points:
            return None
        return float(points[-1]['Average'])
    except Exception as e:
        print(f"CPU query error ({region}/{cluster}/{service}): {e}")
        return None


def _resp_cmd(*args):
    parts = [f"*{len(args)}\\r\\n"]
    for a in args:
        a = str(a)
        parts.append(f"\${len(a)}\\r\\n{a}\\r\\n")
    return "".join(parts)


def _valkey_connect():
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
        resp = cw_local.get_metric_statistics(
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

        metricFn.addToRolePolicy(new iam.PolicyStatement({
            actions: ['cloudwatch:PutMetricData', 'cloudwatch:GetMetricStatistics', 'ecs:DescribeServices'],
            resources: ['*'],
        }));

        new events.Rule(this, 'MetricSchedule', {
            schedule: events.Schedule.rate(cdk.Duration.minutes(1)),
            targets: [new targets.LambdaFunction(metricFn)],
        });

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
        new cdk.CfnOutput(this, 'ModelsBucketName', {
            value: modelsBucket.bucketName,
            description: 'S3 bucket for shared model artifacts',
        });
    }
}
