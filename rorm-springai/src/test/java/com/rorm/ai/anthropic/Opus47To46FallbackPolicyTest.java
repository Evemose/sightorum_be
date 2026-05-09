package com.rorm.ai.anthropic;

import com.anthropic.errors.RateLimitException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class Opus47To46FallbackPolicyTest {

    private Opus47To46FallbackPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new Opus47To46FallbackPolicy();
    }

    @Test
    void opus47_rateLimited_fallsBackTo46() {
        var rateLimit = mock(RateLimitException.class);

        var result = policy.resolveFallback("claude-opus-4-7", rateLimit);

        assertThat(result).isPresent();
        assertThat(result.get().nextModel()).isEqualTo("claude-opus-4-6");
        assertThat(result.get().backoff()).isPositive();
    }

    @Test
    void opus47_otherException_doesNotFallBack() {
        var result = policy.resolveFallback("claude-opus-4-7", new RuntimeException("oops"));

        assertThat(result).isEmpty();
    }

    @Test
    void opus46_rateLimited_doesNotFallBack() {
        var rateLimit = mock(RateLimitException.class);

        var result = policy.resolveFallback("claude-opus-4-6", rateLimit);

        assertThat(result).isEmpty();
    }

    @Test
    void sonnet_rateLimited_doesNotFallBack() {
        var rateLimit = mock(RateLimitException.class);

        var result = policy.resolveFallback("claude-sonnet-4-6", rateLimit);

        assertThat(result).isEmpty();
    }

    @Test
    void wrappedRateLimit_isDetected() {
        var rateLimit = mock(RateLimitException.class);
        var wrapped = new RuntimeException("wrapper", rateLimit);

        var result = policy.resolveFallback("claude-opus-4-7", wrapped);

        assertThat(result).isPresent();
        assertThat(result.get().nextModel()).isEqualTo("claude-opus-4-6");
    }
}
