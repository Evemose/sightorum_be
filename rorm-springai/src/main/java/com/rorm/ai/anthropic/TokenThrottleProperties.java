package com.rorm.ai.anthropic;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.math.BigDecimal;

@ConfigurationProperties(prefix = "rorm.ai.throttle")
public record TokenThrottleProperties(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("0") long maxTokensPerMinute,
    @DefaultValue("0") BigDecimal maxCostPerHour,
    @DefaultValue("0") BigDecimal maxCostPerDay,
    @DefaultValue("UNCACHED_ONLY") TokenCountStrategy strategy
) {

    public enum TokenCountStrategy {
        ALL_TOKENS,
        UNCACHED_ONLY
    }
}
