package com.rorm.ml;

import com.rorm.DurableRuntime;
import com.rorm.misc.YamlPropertySource;
import com.rorm.ml.runtime.InMemoryDurableRuntime;
import com.rorm.ml.stream.JobCompletionHandler;
import com.rorm.ml.stream.JobEventsSupport;
import com.rorm.ml.stream.JobFutureRegistry;
import com.rorm.ml.stream.JobStreamListener;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Fallback;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.Subscription;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;

@Configuration
@EnableConfigurationProperties(RormMlProperties.class)
@YamlPropertySource("classpath:application-ml.yml")
public class RormMlConfiguration {

    @Bean(defaultCandidate = false)
    public RestClient mlRestClient(
        RormMlProperties properties,
        RestClient.Builder builder
    ) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.connectTimeout());
        factory.setReadTimeout(properties.requestTimeout());

        return builder.clone()
            .baseUrl(properties.serviceBaseUrl())
            .requestFactory(factory)
            .defaultHeader("X-RORM-Client", "1")
            .build();
    }

    @Bean
    @Fallback
    @ConditionalOnInMemoryExecution
    public DurableRuntime inMemoryDurableRuntime(ApplicationContext applicationContext) {
        return new InMemoryDurableRuntime(applicationContext);
    }

    @Bean
    @Fallback
    @ConditionalOnInMemoryExecution
    public JobCompletionHandler inMemoryJobCompletionHandler(JobFutureRegistry registry) {
        return new JobEventsSupport(registry);
    }

    @Bean
    public StreamMessageListenerContainer<String, MapRecord<String, String, String>> streamListenerContainer(
        RedisConnectionFactory connectionFactory
    ) {
        var options = StreamMessageListenerContainer.StreamMessageListenerContainerOptions.builder()
            .pollTimeout(Duration.ofSeconds(2))
            .build();

        return StreamMessageListenerContainer.create(connectionFactory, options);
    }

    @Bean
    public Subscription trainingStreamSubscription(
        StreamMessageListenerContainer<String, MapRecord<String, String, String>> container,
        JobStreamListener listener,
        RormMlProperties properties,
        StringRedisTemplate redisTemplate
    ) {
        ensureConsumerGroup(properties, redisTemplate, properties.eventStreamName());
        container.start();
        return container.receive(
            Consumer.from(properties.consumerGroup(), generateConsumerName()),
            StreamOffset.create(properties.eventStreamName(), ReadOffset.lastConsumed()),
            listener
        );
    }

    private static void ensureConsumerGroup(RormMlProperties properties, StringRedisTemplate redisTemplate, String streamName) {
        try {
            redisTemplate.opsForStream().createGroup(streamName, properties.consumerGroup());
        } catch (Exception _) {
            // Group may already exist, which is fine
        }
    }

    private String generateConsumerName() {
        try {
            var hostname = InetAddress.getLocalHost().getHostName();
            return "consumer-" + hostname + "-" + ProcessHandle.current().pid();
        } catch (UnknownHostException _) {
            return "consumer-" + ProcessHandle.current().pid();
        }
    }

    @Bean
    public Subscription jobStartsStreamSubscription(
        StreamMessageListenerContainer<String, MapRecord<String, String, String>> container,
        JobStreamListener listener,
        RormMlProperties properties,
        StringRedisTemplate redisTemplate
    ) {
        ensureConsumerGroup(properties, redisTemplate, properties.jobStartsStreamName());
        return container.receive(
            Consumer.from(properties.consumerGroup(), generateConsumerName() + "-starts"),
            StreamOffset.create(properties.jobStartsStreamName(), ReadOffset.lastConsumed()),
            listener
        );
    }
}
