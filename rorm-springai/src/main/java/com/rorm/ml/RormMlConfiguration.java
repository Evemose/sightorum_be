package com.rorm.ml;

import com.rorm.durable.DurableJobRuntime;
import com.rorm.durable.InMemoryDurableJobRuntime;
import com.rorm.misc.YamlPropertySource;
import com.rorm.ml.jobs.JobExecutor;
import com.rorm.ml.stream.JobStreamListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
            .build();
    }

    @Bean
    public JobExecutor jobExecutor(org.springframework.context.ApplicationContext applicationContext) {
        return new JobExecutor(applicationContext);
    }

    @Bean
    @ConditionalOnMissingBean
    public DurableJobRuntime inMemoryDurableJobRuntime(JobExecutor jobExecutor) {
        return new InMemoryDurableJobRuntime(jobExecutor::execute);
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
        var consumerName = generateConsumerName();

        ensureConsumerGroup(properties, redisTemplate);

        container.start();

        return container.receive(
            Consumer.from(properties.consumerGroup(), consumerName),
            StreamOffset.create(properties.eventStreamName(), ReadOffset.lastConsumed()),
            listener
        );
    }

    private String generateConsumerName() {
        try {
            var hostname = InetAddress.getLocalHost().getHostName();
            return "consumer-" + hostname + "-" + ProcessHandle.current().pid();
        } catch (UnknownHostException _) {
            return "consumer-" + ProcessHandle.current().pid();
        }
    }

    private static void ensureConsumerGroup(RormMlProperties properties, StringRedisTemplate redisTemplate) {
        try {
            redisTemplate.opsForStream().createGroup(
                properties.eventStreamName(),
                properties.consumerGroup()
            );
        } catch (Exception _) {
            // Group may already exist, which is fine
        }
    }
}
