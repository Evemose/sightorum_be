package com.rorm.ml;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties(prefix = "rorm.ml")
public record RormMlProperties(
    boolean enabled,
    @DefaultValue("false") boolean durableExecution,
    @DefaultValue("http://localhost:8000") String serviceBaseUrl,
    @DefaultValue("redis://localhost:6379") String valkeyUrl,
    @DefaultValue("") String valkeyPassword,
    @DefaultValue("ml_training:training_results") String eventStreamName,
    @DefaultValue("ml_training:job_starts") String jobStartsStreamName,
    @DefaultValue("spring_consumers") String consumerGroup,
    @DefaultValue("10m") Duration requestTimeout,
    @DefaultValue("5s") Duration connectTimeout,
    @DefaultValue("http://localhost:9070") String restateAdminUrl,
    @DefaultValue("http://host.docker.internal:9081") String restateEndpointUrl
) {
}
