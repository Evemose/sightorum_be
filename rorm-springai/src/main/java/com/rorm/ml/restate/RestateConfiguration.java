package com.rorm.ml.restate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rorm.DurableRuntime;
import com.rorm.ai.swarm.SwarmEventBus;
import com.rorm.ai.swarm.ValKeySwarmEventBus;
import com.rorm.ml.ConditionalOnDurableExecution;
import com.rorm.ml.RormMlProperties;
import com.rorm.ml.stream.DurableRendezvous;
import com.rorm.ml.stream.JobCompletionHandler;
import com.rorm.ml.stream.JobEvent;
import com.rorm.ml.stream.JobFutureRegistry;
import dev.restate.client.Client;
import dev.restate.sdk.endpoint.definition.InvocationRetryPolicy;
import dev.restate.sdk.springboot.EnableRestate;
import dev.restate.sdk.springboot.RestateServiceConfigurator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.web.client.RestClient;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.Duration;

@Configuration
@EnableRestate
@ConditionalOnDurableExecution
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
        @RestateAdminClient RestClient restateAdminClient,
        @Value("${rorm.ml.runtime.initial-poll-interval:1s}") Duration initialPollInterval,
        @Value("${rorm.ml.runtime.max-poll-interval:30s}") Duration maxPollInterval
    ) {
        return new RestateDurableRuntime(
            restateClient, restateAdminClient, initialPollInterval, maxPollInterval);
    }

    @Bean(defaultCandidate = false)
    public RedisTemplate<String, JobEvent> jobEventRedisTemplate(
        ObjectMapper objectMapper,
        RedisConnectionFactory redisConnectionFactory
    ) {
        var template = new RedisTemplate<String, JobEvent>();
        template.setConnectionFactory(redisConnectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new Jackson2JsonRedisSerializer<>(objectMapper, JobEvent.class));
        return template;
    }

    @Bean
    public DurableRendezvous durableRendezvous(
        RedisConnectionFactory connectionFactory,
        ObjectMapper objectMapper,
        ObjectProvider<RedisTemplate<String, Object>> defaultRedisTemplateProvider,
        @Qualifier("jobEventRedisTemplate") RedisTemplate<String, JobEvent> jobEventRedisTemplate
    ) {
        var redisTemplate = defaultRedisTemplateProvider.getIfAvailable(() -> {
            var template = new RedisTemplate<String, Object>();
            template.setConnectionFactory(connectionFactory);
            template.setKeySerializer(new StringRedisSerializer());
            template.setValueSerializer(new GenericJackson2JsonRedisSerializer(objectMapper));
            template.setHashKeySerializer(new StringRedisSerializer());
            template.setHashValueSerializer(new GenericJackson2JsonRedisSerializer(objectMapper));
            template.afterPropertiesSet();
            return template;
        });
        return new DurableRendezvous(redisTemplate, jobEventRedisTemplate);
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
    public SwarmEventBus swarmEventBus(
        StringRedisTemplate redisTemplate,
        ObjectMapper objectMapper,
        @Value("${rorm.ai.swarm.bus.stream-ttl:1h}") Duration streamTtl,
        @Value("${rorm.ai.swarm.bus.batch-size:1000}") int batchSize,
        @Value("${rorm.ai.swarm.bus.sink-capacity:1024}") int sinkCapacity,
        @Value("${rorm.ai.swarm.bus.xread-block:5s}") Duration xreadBlock,
        @Value("${rorm.ai.swarm.bus.backpressure-park:1ms}") Duration backpressurePark
    ) {
        return new ValKeySwarmEventBus(redisTemplate, objectMapper,
            streamTtl, batchSize, sinkCapacity, xreadBlock, backpressurePark);
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
