package com.rorm.ai.anthropic;

import com.anthropic.errors.RateLimitException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * Fallback policy for the Opus tier: when {@code claude-opus-4-7} returns
 * HTTP 429 (rate-limited), retry the request against {@code claude-opus-4-6}
 * after a short backoff. Other models pass through unchanged. Other errors
 * pass through unchanged.
 * <p>
 * The backoff is intentionally short: Anthropic's rate-limit windows are
 * minute-grained, but 4-6 has its own bucket that is unlikely to be in the
 * same depleted state — so the goal is just to avoid hammering the API
 * with a re-attempt before the TCP connection has settled.
 */
@Slf4j
@Component
public class Opus47To46FallbackPolicy implements ModelFallbackPolicy {

    private static final String PRIMARY = "claude-opus-4-7";
    private static final String FALLBACK = "claude-opus-4-6";
    private static final Duration BACKOFF = Duration.ofMillis(250);

    @Override
    public Optional<ModelFallback> resolveFallback(String currentModel, Throwable error) {
        if (!PRIMARY.equals(currentModel)) {
            return Optional.empty();
        }
        if (!isRateLimited(error)) {
            return Optional.empty();
        }
        log.warn("[anthropic] {} rate-limited; falling back to {}", PRIMARY, FALLBACK);
        return Optional.of(new ModelFallback(FALLBACK, BACKOFF));
    }

    private static boolean isRateLimited(Throwable error) {
        for (var cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof RateLimitException) {
                return true;
            }
        }
        return false;
    }
}
