package com.rorm.ai.anthropic;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

@Slf4j
public class TokenThrottle {

    private static final BigDecimal MILLION = new BigDecimal("1000000");
    private static final double OUTPUT_ESTIMATE_RATIO = 0.5;
    private static final long OUTPUT_ESTIMATE_CAP = 8192;
    private static final int COST_MICRODOLLARS_SCALE = 1_000_000;

    private static final Map<String, ModelPricing> PRICING = Map.of(
        "claude-sonnet-4-6", new ModelPricing(bd("3"), bd("15"), bd("3.75"), bd("0.30")),
        "claude-sonnet-4-5-20250514", new ModelPricing(bd("3"), bd("15"), bd("3.75"), bd("0.30")),
        "claude-opus-4-6", new ModelPricing(bd("15"), bd("75"), bd("18.75"), bd("1.50")),
        "claude-haiku-4-5-20251001", new ModelPricing(bd("0.80"), bd("4"), bd("1"), bd("0.08"))
    );

    private static final ModelPricing FALLBACK_PRICING = PRICING.get("claude-opus-4-6");

    private final @Nullable Bucket tokenBucket;
    private final @Nullable Bucket costHourBucket;
    private final @Nullable Bucket costDayBucket;
    private final TokenThrottleProperties.TokenCountStrategy strategy;

    public TokenThrottle(TokenThrottleProperties properties) {
        this.strategy = properties.strategy();
        this.tokenBucket = properties.enabled() && properties.maxTokensPerMinute() > 0
            ? Bucket.builder()
            .addLimit(Bandwidth.builder()
                .capacity(properties.maxTokensPerMinute())
                .refillGreedy(properties.maxTokensPerMinute(), Duration.ofMinutes(1))
                .build())
            .build()
            : null;

        this.costHourBucket = properties.enabled() && properties.maxCostPerHour().signum() > 0
            ? Bucket.builder()
            .addLimit(Bandwidth.builder()
                .capacity(toMicrodollars(properties.maxCostPerHour()))
                .refillGreedy(toMicrodollars(properties.maxCostPerHour()), Duration.ofHours(1))
                .build())
            .build()
            : null;

        this.costDayBucket = properties.enabled() && properties.maxCostPerDay().signum() > 0
            ? Bucket.builder()
            .addLimit(Bandwidth.builder()
                .capacity(toMicrodollars(properties.maxCostPerDay()))
                .refillGreedy(toMicrodollars(properties.maxCostPerDay()), Duration.ofDays(1))
                .build())
            .build()
            : null;
    }

    private static long toMicrodollars(BigDecimal dollars) {
        return dollars.multiply(BigDecimal.valueOf(COST_MICRODOLLARS_SCALE))
            .longValue();
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    public <R> R execute(
        UsageConsuming estimate,
        Supplier<R> action,
        Function<R, UsageConsuming> actualUsage
    ) {
        consumeEstimate(estimate);
        var result = action.get();
        adjustAfterResponse(estimate, actualUsage.apply(result));
        return result;
    }

    private void consumeEstimate(UsageConsuming estimate) {
        long estimatedOutput = Math.min(
            (long) (estimate.inputTokens() * OUTPUT_ESTIMATE_RATIO), OUTPUT_ESTIMATE_CAP);
        long estimatedTokens = estimate.inputTokens() + estimatedOutput;

        consume(tokenBucket, estimatedTokens, "tokens/min");

        var pricing = PRICING.getOrDefault(estimate.model(), FALLBACK_PRICING);
        long estimatedCostMicro = toMicrodollars(estimateCost(
            estimate.inputTokens(), estimatedOutput, pricing));

        consume(costHourBucket, estimatedCostMicro, "cost/hour");
        consume(costDayBucket, estimatedCostMicro, "cost/day");
    }

    private void adjustAfterResponse(UsageConsuming estimate, UsageConsuming actual) {
        var pricing = PRICING.getOrDefault(actual.model(), FALLBACK_PRICING);
        var actualCost = computeCost(actual, pricing);

        long estimatedOutput = Math.min(
            (long) (estimate.inputTokens() * OUTPUT_ESTIMATE_RATIO), OUTPUT_ESTIMATE_CAP);
        long estimatedTokens = estimate.inputTokens() + estimatedOutput;
        long actualTokens = strategy == TokenThrottleProperties.TokenCountStrategy.ALL_TOKENS
            ? actual.totalTokensIncludingCache()
            : actual.totalTokens();
        long estimatedCostMicro = toMicrodollars(estimateCost(
            estimate.inputTokens(), estimatedOutput, pricing));
        long actualCostMicro = toMicrodollars(actualCost);

        giveBack(tokenBucket, estimatedTokens, actualTokens);
        giveBack(costHourBucket, estimatedCostMicro, actualCostMicro);
        giveBack(costDayBucket, estimatedCostMicro, actualCostMicro);

        log.debug("Usage: model={} in={} out={} cacheW={} cacheR={} cost=${}",
            actual.model(), actual.inputTokens(), actual.outputTokens(),
            actual.cacheCreationTokens(), actual.cacheReadTokens(),
            actualCost.setScale(6, RoundingMode.HALF_UP));
    }

    private static void consume(@Nullable Bucket bucket, long amount, String label) {
        if (bucket == null || amount <= 0) {
            return;
        }
        try {
            log.trace("Consuming {} from {}", amount, label);
            bucket.asBlocking().consume(amount);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TokenBudgetExceededException("INTERRUPTED", 0,
                "Interrupted while waiting for " + label + " budget");
        }
    }

    private static BigDecimal estimateCost(long inputTokens, long outputTokens, ModelPricing pricing) {
        return BigDecimal.valueOf(inputTokens).multiply(pricing.inputPerMillion)
            .add(BigDecimal.valueOf(outputTokens).multiply(pricing.outputPerMillion))
            .divide(MILLION, 10, RoundingMode.HALF_UP);
    }

    private static BigDecimal computeCost(UsageConsuming usage, ModelPricing pricing) {
        return BigDecimal.valueOf(usage.inputTokens()).multiply(pricing.inputPerMillion)
            .add(BigDecimal.valueOf(usage.outputTokens()).multiply(pricing.outputPerMillion))
            .add(BigDecimal.valueOf(usage.cacheCreationTokens()).multiply(pricing.cacheWritePerMillion))
            .add(BigDecimal.valueOf(usage.cacheReadTokens()).multiply(pricing.cacheReadPerMillion))
            .divide(MILLION, 10, RoundingMode.HALF_UP);
    }

    private static void giveBack(@Nullable Bucket bucket, long estimated, long actual) {
        if (bucket == null) {
            return;
        }
        long overEstimate = estimated - actual;
        if (overEstimate > 0) {
            bucket.addTokens(overEstimate);
        }
    }

    record ModelPricing(
        BigDecimal inputPerMillion, BigDecimal outputPerMillion,
        BigDecimal cacheWritePerMillion, BigDecimal cacheReadPerMillion
    ) {}
}
