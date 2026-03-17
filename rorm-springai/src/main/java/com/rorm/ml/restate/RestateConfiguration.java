package com.rorm.ml.restate;

import com.rorm.DurableRuntime;
import com.rorm.ml.RormMlProperties;
import dev.restate.client.Client;
import dev.restate.sdk.springboot.EnableRestate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Configuration
@EnableRestate
@ConditionalOnProperty(name = "rorm.ml.durable-execution", havingValue = "true")
public class RestateConfiguration {

    @RestateAdminClient
    @Bean(defaultCandidate = false)
    public RestClient restateAdminClient(RormMlProperties properties, RestClient.Builder builder) {
        return builder.clone()
            .baseUrl(properties.restateAdminUrl())
            .build();
    }

    @Bean
    public DurableRuntime restateDurableRuntime(
        Client restateClient,
        @RestateAdminClient RestClient restateAdminClient
    ) {
        return new RestateDurableRuntime(restateClient, restateAdminClient);
    }

    @Bean
    public RestateDeploymentRegistrar restateDeploymentRegistrar(
        @RestateAdminClient RestClient restateAdminClient,
        RormMlProperties properties
    ) {
        return new RestateDeploymentRegistrar(
            restateAdminClient,
            properties.restateEndpointHost(),
            properties.restateEndpointPort()
        );
    }

    @Target({ElementType.METHOD, ElementType.PARAMETER})
    @Retention(RetentionPolicy.RUNTIME)
    @Qualifier
    protected @interface RestateAdminClient {

    }
}
