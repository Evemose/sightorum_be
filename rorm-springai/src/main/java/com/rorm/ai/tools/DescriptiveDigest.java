package com.rorm.ai.tools;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record DescriptiveDigest(
    boolean success,
    Archetype archetype,
    @Nullable String headline,
    List<FiredCheck> firedChecks,
    @Nullable Receipts receipts,
    @Nullable Map<String, Object> rawMetrics,
    @Nullable String error
) {

    public static DescriptiveDigest ok(Archetype archetype, String headline,
                                       List<FiredCheck> firedChecks, Receipts receipts,
                                       Map<String, Object> rawMetrics) {
        return new DescriptiveDigest(true, archetype, headline, firedChecks, receipts, rawMetrics, null);
    }

    public static DescriptiveDigest error(Archetype archetype, String error) {
        return new DescriptiveDigest(false, archetype, null, List.of(), null, null, error);
    }

    public enum Archetype {
        SUMMARY_STAT,
        RANKING,
        TREND,
        COMPARISON
    }

    public enum Severity {
        HIGH,
        MED,
        LOW
    }

    public record FiredCheck(
        String code,
        Severity severity,
        String message,
        @Nullable Map<String, Object> payload
    ) {
        public static FiredCheck high(String code, String message, Map<String, Object> payload) {
            return new FiredCheck(code, Severity.HIGH, message, payload);
        }

        public static FiredCheck med(String code, String message, Map<String, Object> payload) {
            return new FiredCheck(code, Severity.MED, message, payload);
        }

        public static FiredCheck low(String code, String message, Map<String, Object> payload) {
            return new FiredCheck(code, Severity.LOW, message, payload);
        }
    }

    public record Receipts(
        @Nullable String window,
        String population,
        List<String> axesConsidered,
        Archetype archetype,
        List<String> unavailableChecks
    ) {}
}
