import * as cdk from 'aws-cdk-lib';
import * as ec2 from 'aws-cdk-lib/aws-ec2';
import * as ecs from 'aws-cdk-lib/aws-ecs';
import * as cloudwatch from 'aws-cdk-lib/aws-cloudwatch';
import * as iam from 'aws-cdk-lib/aws-iam';
import * as appscaling from 'aws-cdk-lib/aws-applicationautoscaling';

export interface MlComputeOnlyRegionStackProps extends cdk.StackProps {
    readonly modelsBucketName: string;
    readonly imageUri: string;
    readonly regionName: string;
}

export class MlComputeOnlyRegionStack extends cdk.Stack {
    constructor(scope: cdk.App, id: string, props: MlComputeOnlyRegionStackProps) {
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

        const cluster = new ecs.Cluster(this, 'MlComputeCluster', {
            vpc,
            clusterName: `MlComputeCluster-${props.regionName}`,
        });

        const taskDef = new ecs.FargateTaskDefinition(this, 'MlComputeTask', {
            cpu: 16384,
            memoryLimitMiB: 32768,
        });

        taskDef.addContainer('ml', {
            image: ecs.ContainerImage.fromRegistry(props.imageUri),
            portMappings: [{containerPort: 8000}],
            environment: {
                DATABASE_HOST: valkeyHost.valueAsString,
                DATABASE_PORT: '5444',
                DATABASE_PASSWORD: 'mypassword',
                REDIS_URL: cdk.Fn.join('', ['redis://:', valkeyPassword.valueAsString, '@', valkeyHost.valueAsString, ':6379']),
                REDIS_PASSWORD: valkeyPassword.valueAsString,
                STORAGE_BACKEND: 's3',
                STORAGE_BUCKET: props.modelsBucketName,
            },
            logging: ecs.LogDrivers.awsLogs({streamPrefix: 'ml-worker'}),
        });

        taskDef.taskRole.addToPrincipalPolicy(new iam.PolicyStatement({
            actions: ['s3:GetObject', 's3:PutObject', 's3:DeleteObject'],
            resources: [`arn:aws:s3:::${props.modelsBucketName}/*`],
        }));
        taskDef.taskRole.addToPrincipalPolicy(new iam.PolicyStatement({
            actions: ['s3:ListBucket'],
            resources: [`arn:aws:s3:::${props.modelsBucketName}`],
        }));
        taskDef.taskRole.addToPrincipalPolicy(new iam.PolicyStatement({
            actions: ['ecs:GetTaskProtection', 'ecs:UpdateTaskProtection'],
            resources: ['*'],
        }));

        taskDef.addToExecutionRolePolicy(new iam.PolicyStatement({
            actions: [
                'ecr:GetAuthorizationToken',
                'ecr:BatchCheckLayerAvailability',
                'ecr:GetDownloadUrlForLayer',
                'ecr:BatchGetImage',
            ],
            resources: ['*'],
        }));

        const service = new ecs.FargateService(this, 'MlComputeService', {
            cluster,
            taskDefinition: taskDef,
            serviceName: `MlComputeService-${props.regionName}`,
            desiredCount: 0,
            assignPublicIp: true,
            securityGroups: [mlSg],
            minHealthyPercent: 100,
            maxHealthyPercent: 200,
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
                dimensionsMap: {Region: props.regionName},
            }),
            scalingSteps: [
                {lower: 1, change: +1},
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
                dimensionsMap: {Region: props.regionName},
            }),
            threshold: 0.05,
            comparisonOperator: cloudwatch.ComparisonOperator.LESS_THAN_THRESHOLD,
            evaluationPeriods: 15,
            datapointsToAlarm: 15,
            treatMissingData: cloudwatch.TreatMissingData.BREACHING,
        });

        scaleDownAlarm.addAlarmAction(new cdk.aws_cloudwatch_actions.ApplicationScalingAction(scaleDownPolicy));

        new cdk.CfnOutput(this, 'ClusterName', {
            value: cluster.clusterName,
            description: 'ECS cluster name for compute-only region',
        });
        new cdk.CfnOutput(this, 'ServiceName', {
            value: service.serviceName,
            description: 'ECS service name for compute-only region',
        });
    }
}
