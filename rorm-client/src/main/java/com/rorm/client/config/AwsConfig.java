package com.rorm.client.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.sts.StsClient;

@Configuration
public class AwsConfig {

    @Bean
    public EcsClient ecsClient() {
        return EcsClient.builder()
            .region(Region.EU_CENTRAL_1)
            .credentialsProvider(DefaultCredentialsProvider.create())
            .build();
    }

    @Bean
    public StsClient stsClient() {
        return StsClient.builder()
            .region(Region.EU_CENTRAL_1)
            .credentialsProvider(DefaultCredentialsProvider.create())
            .build();
    }

    @Bean
    public IamClient iamClient() {
        return IamClient.builder()
            .region(Region.AWS_GLOBAL)
            .credentialsProvider(DefaultCredentialsProvider.create())
            .build();
    }
}