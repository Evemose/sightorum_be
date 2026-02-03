package com.rorm.client.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rorm.client")
public record RormClientProperties(
    String tempFileDir,
    int tempFileTtlHours,
    SseProperties sse
) {
    public record SseProperties(
        long heartbeatIntervalMs,
        long reconnectDelayMs
    ) {}
}
