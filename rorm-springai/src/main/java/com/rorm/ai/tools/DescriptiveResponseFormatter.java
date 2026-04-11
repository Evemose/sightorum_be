package com.rorm.ai.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rorm.ai.tools.DescriptiveDigest.Archetype;
import com.rorm.ai.tools.DescriptiveDigest.FiredCheck;
import com.rorm.ai.tools.DescriptiveDigest.Receipts;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
@RequiredArgsConstructor
class DescriptiveResponseFormatter {

    private static final int MAX_VISIBLE_CHECKS = 3;

    private static final Set<String> PINNED_TOP = Set.of(
        "K1_FRAME_MISMATCH",
        "K3_SIMPSON_REVERSAL"
    );

    private static final Set<String> HETEROGENEITY_CODES = Set.of(
        "C1_HETEROGENEITY",
        "K2_POPULATION_DRIFT",
        "R1_WITHIN_PARTITION_CHURN"
    );

    private static final Set<String> STRUCTURAL_BREAK_CODES = Set.of(
        "T4_STRUCTURAL_BREAK",
        "T5_COMPOSITIONAL_SHIFT",
        "T3_WINDOW_SENSITIVITY"
    );

    private static final Set<String> SHAPE_CODES = Set.of(
        "C2_DISTRIBUTIONAL_SHAPE",
        "C3_OUTLIER_SENSITIVE",
        "T1_SEASONALITY_DOMINATES",
        "T2_CYCLIC_WINDOW",
        "T7_MULTIPLICATIVE_VARIANCE",
        "R2_GAP_TO_SPREAD",
        "K4_MAGNITUDE_MISMATCH"
    );

    private final ObjectMapper objectMapper;

    String format(Archetype archetype, String headline, List<FiredCheck> firedChecks,
                  Receipts receipts, Map<String, Object> rawMetrics) {
        var ordered = orderAndCap(firedChecks);
        var digest = DescriptiveDigest.ok(archetype, headline, ordered, receipts, rawMetrics);
        return serialize(digest);
    }

    private List<FiredCheck> orderAndCap(List<FiredCheck> fired) {
        var ordered = fired.stream()
            .sorted(Comparator
                .comparingInt(DescriptiveResponseFormatter::priorityRank)
                .thenComparingInt(c -> c.severity().ordinal()))
            .toList();
        if (ordered.size() <= MAX_VISIBLE_CHECKS) {
            return ordered;
        }
        return ordered.subList(0, MAX_VISIBLE_CHECKS);
    }

    private String serialize(DescriptiveDigest digest) {
        try {
            return objectMapper.writeValueAsString(digest);
        } catch (JsonProcessingException e) {
            return "{\"success\":false,\"error\":\"" + e.getMessage().replace("\"", "\\\"") + "\"}";
        }
    }

    private static int priorityRank(FiredCheck check) {
        if (PINNED_TOP.contains(check.code())) {
            return 0;
        }
        if (HETEROGENEITY_CODES.contains(check.code())) {
            return 1;
        }
        if (STRUCTURAL_BREAK_CODES.contains(check.code())) {
            return 2;
        }
        if (SHAPE_CODES.contains(check.code())) {
            return 3;
        }
        return switch (check.severity()) {
            case HIGH -> 4;
            case MED -> 5;
            case LOW -> 6;
        };
    }

    String error(Archetype archetype, String message) {
        return serialize(DescriptiveDigest.error(archetype, message));
    }
}
