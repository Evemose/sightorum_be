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

        const streamName = new cdk.CfnParameter(this, 'StreamName', {
            type: 'String',
            default: 'ml-jobs',
            description: 'Valkey stream name for ML jobs',
        });

        const groupName = new cdk.CfnParameter(this, 'GroupName', {
            type: 'String',
            default: 'ml-workers',
            description: 'Valkey consumer group name',
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
            cpu: 8192,           // 8 vCPU
            memoryLimitMiB: 16384, // 16 GB (minimum for 8 vCPU)
        });

        taskDef.addContainer('ml', {
            image: ecs.ContainerImage.fromEcrRepository(repo, 'latest'),
            portMappings: [{containerPort: 8000}],
            environment: {
                VALKEY_HOST: valkeyHost.valueAsString,
                VALKEY_PORT: '6379',
            },
            logging: ecs.LogDrivers.awsLogs({streamPrefix: 'ml-worker'}),
        });

        // ========================
        // ALB
        // ========================
        const alb = new elbv2.ApplicationLoadBalancer(this, 'MlAlb', {
            vpc,
            internetFacing: true,
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

        scaling.scaleOnMetric('StreamBackpressure', {
            metric: new cloudwatch.Metric({
                namespace: 'Custom/ML',
                metricName: 'MlWorkerDemand',
                statistic: 'Average',
                period: cdk.Duration.seconds(60),
            }),
            scalingSteps: [
                {upper: 0, change: -1},   // no demand → scale in
                {lower: 1, change: 0},    // minimal demand → hold
                {lower: 2, change: +1},   // 2+ pending → add 1
                {lower: 4, change: +2},   // 4+ pending → add 2
            ],
            cooldown: cdk.Duration.seconds(120),
            adjustmentType: appscaling.AdjustmentType.CHANGE_IN_CAPACITY,
        });

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
                STREAM_NAME: streamName.valueAsString,
                GROUP_NAME: groupName.valueAsString,
                ALB_ARN_SUFFIX: alb.loadBalancerFullName,
            },
            code: lambda.Code.fromInline(`
            import boto3, os, socket, json
            from datetime import datetime, timedelta, timezone
            
            cw = boto3.client('cloudwatch')
            
            def handler(event, context):
                pending = get_valkey_pending()
                recent_http = get_recent_request_count()
            
                # Demand signal: Valkey backpressure takes priority,
                # but any HTTP activity in last 10 min keeps workers alive.
                if pending > 0:
                    value = float(pending)
                elif recent_http > 0:
                    value = 1.0   # keep-alive signal
                else:
                    value = 0.0   # safe to scale down
            
                cw.put_metric_data(
                    Namespace='Custom/ML',
                    MetricData=[{
                        'MetricName': 'MlWorkerDemand',
                        'Value': value,
                        'Unit': 'Count',
                    }]
                )
                return {'pending': pending, 'recent_http': recent_http, 'published': value}
            
            
            def get_valkey_pending():
                host = os.environ['VALKEY_HOST']
                port = int(os.environ['VALKEY_PORT'])
                stream = os.environ['STREAM_NAME']
                group = os.environ['GROUP_NAME']
            
                try:
                    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                    sock.settimeout(5)
                    sock.connect((host, port))
                    cmd = f"*3\\r\\n$8\\r\\nXPENDING\\r\\n$\{len(stream)}\\r\\n{stream}\\r\\n\${len(group)}\\r\\n{group}\\r\\n"
                    sock.sendall(cmd.encode())
                    resp = sock.recv(4096).decode()
                    sock.close()
                    for line in resp.split('\\r\\n'):
                        if line.startswith(':'):
                            return int(line[1:])
                except Exception as e:
                    print(f"Valkey connection error: {e}")
                return 0
            
            
            def get_recent_request_count():
                """Check if ALB had any requests in the last 10 minutes."""
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
                        Period=3600,
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
            actions: ['cloudwatch:PutMetricData', 'cloudwatch:GetMetricStatistics'],
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