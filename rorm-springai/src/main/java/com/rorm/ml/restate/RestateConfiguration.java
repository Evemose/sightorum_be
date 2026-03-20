package com.rorm.ml.restate;

import com.rorm.DurableRuntime;
import com.rorm.ml.RormMlProperties;
import com.rorm.ml.stream.DurableRendezvous;
import com.rorm.ml.stream.JobCompletionHandler;
import com.rorm.ml.stream.JobFutureRegistry;
import dev.restate.client.Client;
import dev.restate.sdk.endpoint.definition.InvocationRetryPolicy;
import dev.restate.sdk.springboot.EnableRestate;
import dev.restate.sdk.springboot.RestateServiceConfigurator;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.client.RestClient;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.Duration;

@Configuration
@EnableRestate
@ConditionalOnProperty(name = "rorm.ml.durable-execution", havingValue = "true")
public class RestateConfiguration {

    @Bean
    RestateServiceConfigurator durableJobConfig() {
        return sd -> sd.invocationRetryPolicy(
            InvocationRetryPolicy.builder()
                .maxAttempts(1)
                .onMaxAttempts(InvocationRetryPolicy.OnMaxAttempts.PAUSE)
                .initialInterval(Duration.ofMillis(1))
                .build()
        );
    }

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
    public DurableRendezvous durableRendezvous(RedisTemplate<String, Object> redisTemplate) {
        return new DurableRendezvous(redisTemplate);
    }

    @Bean
    public JobCompletionHandler restateJobCompletionHandler(
        Client restateClient,
        JobFutureRegistry fallbackRegistry,
        DurableRendezvous durableRendezvous
    ) {
        return new RestateJobCompletionHandler(restateClient, fallbackRegistry, durableRendezvous);
    }

    @Bean
    public RestateDeploymentRegistrar restateDeploymentRegistrar(
        @RestateAdminClient RestClient restateAdminClient,
        RormMlProperties properties
    ) {
        return new RestateDeploymentRegistrar(
            restateAdminClient,
            properties.restateEndpointUrl()
        );
    }

    @Target({ElementType.METHOD, ElementType.PARAMETER})
    @Retention(RetentionPolicy.RUNTIME)
    @Qualifier
    protected @interface RestateAdminClient {

    }
}
