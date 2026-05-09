package com.rorm.ai.anthropic;

import java.time.Duration;
import java.util.Optional;

/**
 * Decides whether to retry a failed Anthropic call against a different
 * model. Returns a non-empty {@link ModelFallback} when the policy elects
 * to fall back; an empty Optional means "rethrow as-is, this model and
 * this error pair are not recoverable here."
 * <p>
 * The policy operates on the unmapped logical model name
 * ({@code claude-opus-4-7}, {@code claude-sonnet-4-6}, ...) so that the
 * caller's bedrock-vs-direct mapping logic stays in one place.
 */
public interface ModelFallbackPolicy {

    Optional<ModelFallback> resolveFallback(String currentModel, Throwable error);

    record ModelFallback(String nextModel, Duration backoff) {
    }
}
