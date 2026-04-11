package com.rorm.ai.tools;

import com.rorm.ai.JournaledTool;
import com.rorm.ai.RormToolContext;
import com.rorm.ai.tools.DescriptiveDigest.Archetype;
import com.rorm.ai.tools.DescriptiveDigest.FiredCheck;
import com.rorm.ai.tools.DescriptiveDigest.Receipts;
import com.rorm.dto.dense.DenseExpressionDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.rorm.ai.tools.DescriptiveQueryBuilder.*;
import static com.rorm.ai.tools.VerificationQueryExecutor.longVal;
import static com.rorm.ai.tools.VerificationQueryExecutor.numVal;

@SuppressWarnings("unused")
@Slf4j
@Component
@JournaledTool
@RequiredArgsConstructor
public class SummaryStatTool {

    private static final String EXPR_HINT =
        "Expression (path for column, or derived). Paths resolve against the FROM root.";

    private static final int SMALL_N_POP = 30;
    private static final int SMALL_N_SEG = 10;
    private static final double OUTLIER_SENSITIVITY_THRESHOLD = 0.10;
    private static final double RATIO_DENOM_COV_THRESHOLD = 1.0;
    private static final int SHAPE_SAMPLE_LIMIT = 50_000;

    private final VerificationQueryExecutor executor;
    private final AxisFanout axisFanout;
    private final DescriptiveStatsService statsService;
    private final DescriptiveResponseFormatter formatter;

    @Tool(name = "summaryStatistic", description = """
        SUMMARY STATISTIC — Compute a single scalar (total/mean/median/count/ratio) over a population
        and run deterministic robustness checks:
        
        C1 Heterogeneity — does the aggregate hide >3x spread across any candidate axis?
        C2 Distributional shape — skew/kurtosis/multimodality of the underlying distribution
        C3 Outlier sensitivity — does the value change by >10% when top/bottom 1% is trimmed?
        C4 Small-N disclosure — N < 30 or surfaced segment < 10
        C5 Denominator stability (ratio only) — segment denominators stable?
        C6 Survivorship disclosure — fired when survivorshipFlag is true
        
        Returns a JSON digest with fired checks ordered by severity (top 3) and a receipts block.
        Use when the question is "what is X?" and you want the answer plus hidden-assumption warnings.""")
    public String summaryStatistic(
        @ToolParam(description = "Root entity name") String rootName,
        @ToolParam(description = "Measure expression. " + EXPR_HINT) DenseExpressionDto measure,
        @ToolParam(description = "Measure kind: total|mean|median|count|ratio") String kind,
        @ToolParam(description = "Denominator expression for kind=ratio. " + EXPR_HINT)
        @Nullable DenseExpressionDto denominator,
        @ToolParam(description = "Optional WHERE filter. " + EXPR_HINT)
        @Nullable DenseExpressionDto filter,
        @ToolParam(description = "Time expression (DATE/TIMESTAMP column). " + EXPR_HINT)
        @Nullable DenseExpressionDto timeExpression,
        @ToolParam(description = "Time window start (ISO date/timestamp). Requires timeExpression.")
        @Nullable String timeStart,
        @ToolParam(description = "Time window end (ISO date/timestamp, exclusive). Requires timeExpression.")
        @Nullable String timeEnd,
        @ToolParam(description = "Candidate axes for heterogeneity fanout. Categorical expressions.")
        List<DenseExpressionDto> candidateAxes,
        @ToolParam(description = "True when population is filtered to current-only entities. Fires C6.")
        boolean survivorshipFlag,
        ToolContext toolContext
    ) {
        try {
            validateKind(kind);
            var ctx = RormToolContext.from(toolContext);
            var axes = candidateAxes == null ? List.<DenseExpressionDto>of() : candidateAxes;
            var effectiveFilter = withTimeFilter(filter, timeExpression, timeStart, timeEnd);
            var unavailable = new ArrayList<String>();

            var baseRow = executor.executeSingle(plainQuery(rootName, effectiveFilter,
                measureSelector(measure, kind, denominator, "value"),
                countStar("n")
            ), ctx);
            var value = numVal(baseRow, "value");
            var n = longVal(baseRow, "n");

            var fired = new ArrayList<FiredCheck>();
            var rawMetrics = new LinkedHashMap<String, Object>();
            rawMetrics.put("value", value);
            rawMetrics.put("n", n);
            rawMetrics.put("kind", kind);

            evaluateHeterogeneity(rootName, effectiveFilter, measure, kind, denominator, axes, value, ctx, fired, rawMetrics);
            evaluateOutlierSensitivity(rootName, effectiveFilter, measure, kind, denominator, value, fired, ctx, rawMetrics);
            evaluateShape(rootName, effectiveFilter, measure, kind, n, fired, ctx, unavailable, rawMetrics);
            evaluateDenominatorStability(rootName, effectiveFilter, measure, denominator, kind, axes, fired, ctx, rawMetrics);
            evaluateSmallN(n, axes, rootName, effectiveFilter, measure, kind, denominator, fired, ctx);
            if (survivorshipFlag) {
                fired.add(FiredCheck.low("C6_SURVIVORSHIP",
                    "Population was flagged as filtered to current-only entities — results exclude churned/dead entities.",
                    Map.of("survivorship_flag", true)));
            }

            var headline = headline(kind, measure, value, n);
            var receipts = buildReceipts(rootName, effectiveFilter, timeStart, timeEnd, axes, unavailable);
            return formatter.format(Archetype.SUMMARY_STAT, headline, fired, receipts, rawMetrics);
        } catch (IllegalArgumentException e) {
            log.warn("Summary stat invalid input: {}", e.getMessage());
            return formatter.error(Archetype.SUMMARY_STAT, e.getMessage());
        } catch (Exception e) {
            log.error("Summary stat failed", e);
            return formatter.error(Archetype.SUMMARY_STAT, "Summary stat failed: " + e.getMessage());
        }
    }

    private static void validateKind(String kind) {
        if (kind == null) {
            throw new IllegalArgumentException("kind is required");
        }
        switch (kind.toLowerCase()) {
            case "total", "sum", "mean", "avg", "average", "count", "ratio", "median" -> {
            }
            default -> throw new IllegalArgumentException(
                "Unknown kind: " + kind + " (expected total|mean|median|count|ratio)");
        }
    }

    private void evaluateHeterogeneity(String rootName, @Nullable DenseExpressionDto filter,
                                       DenseExpressionDto measure, String kind,
                                       @Nullable DenseExpressionDto denominator,
                                       List<DenseExpressionDto> axes, double aggregate,
                                       RormToolContext ctx,
                                       List<FiredCheck> fired, Map<String, Object> rawMetrics) {
        if (axes.isEmpty()) {
            return;
        }
        var fanoutResults = axisFanout.fanout(rootName, filter, measure, kind, denominator, axes, ctx);
        var rankings = new ArrayList<Map.Entry<Integer, Double>>();
        var perAxisSnapshots = new ArrayList<Map<String, Object>>();
        for (var i = 0; i < fanoutResults.size(); i++) {
            var r = fanoutResults.get(i);
            if (r.segments().size() < 2) {
                continue;
            }
            var values = r.segments().stream().map(AxisFanout.Segment::value).toList();
            var hetero = DescriptiveMath.heterogeneity(values, aggregate);
            var snapshot = Map.<String, Object>of(
                "axis_index", i,
                "segments", r.segments().size(),
                "max_min_ratio", hetero.maxMinRatio(),
                "max_deviation_factor", hetero.maxDeviationFactor(),
                "fired", hetero.fired());
            perAxisSnapshots.add(snapshot);
            if (hetero.fired()) {
                rankings.add(Map.entry(i, Math.max(hetero.maxMinRatio(), hetero.maxDeviationFactor())));
            }
        }
        rawMetrics.put("heterogeneity_per_axis", perAxisSnapshots);
        if (rankings.isEmpty()) {
            return;
        }
        rankings.sort(Map.Entry.<Integer, Double>comparingByValue().reversed());
        var top = rankings.stream().limit(2).map(Map.Entry::getKey).toList();
        var payloadSegments = new ArrayList<Map<String, Object>>();
        for (var axisIdx : top) {
            var r = fanoutResults.get(axisIdx);
            var segList = r.segments().stream()
                .map(s -> Map.<String, Object>of("segment", s.key(), "value", s.value(), "n", s.n()))
                .toList();
            payloadSegments.add(Map.of("axis_index", axisIdx, "segments", segList));
        }
        fired.add(FiredCheck.high("C1_HETEROGENEITY",
            "Aggregate hides strong spread across " + top.size() + " candidate axis/axes — segment values diverge >3x or max deviation > 2x median.",
            Map.of("top_axes", payloadSegments)));
    }

    private void evaluateOutlierSensitivity(String rootName, @Nullable DenseExpressionDto filter,
                                            DenseExpressionDto measure, String kind,
                                            @Nullable DenseExpressionDto denominator,
                                            double originalValue, List<FiredCheck> fired,
                                            RormToolContext ctx, Map<String, Object> rawMetrics) {
        if (!outlierEligible(kind)) {
            return;
        }
        var boundsRow = executor.executeSingle(plainQuery(rootName, filter,
            percentileCont(0.01, measure, "p01"),
            percentileCont(0.99, measure, "p99")
        ), ctx);
        var low = DenseExpressionDto.literal(numVal(boundsRow, "p01"));
        var high = DenseExpressionDto.literal(numVal(boundsRow, "p99"));
        var trimmedFilter = filter == null
            ? between(measure, low, high)
            : DenseExpressionDto.binary(filter, "AND", between(measure, low, high));
        var trimmedRow = executor.executeSingle(plainQuery(rootName, trimmedFilter,
            measureSelector(measure, kind, denominator, "value"),
            countStar("n")
        ), ctx);
        var trimmedValue = numVal(trimmedRow, "value");
        var absOriginal = Math.abs(originalValue);
        if (absOriginal < 1e-12) {
            return;
        }
        var ratio = Math.abs(trimmedValue - originalValue) / absOriginal;
        rawMetrics.put("outlier_trimmed_value", trimmedValue);
        rawMetrics.put("outlier_ratio", ratio);
        if (ratio > OUTLIER_SENSITIVITY_THRESHOLD) {
            fired.add(FiredCheck.med("C3_OUTLIER_SENSITIVE",
                "Value shifts " + String.format("%.1f%%", ratio * 100) + " when top/bottom 1% is trimmed — measure is outlier-driven.",
                Map.of("original", originalValue, "trimmed", trimmedValue, "shift_ratio", ratio)));
        }
    }

    private void evaluateShape(String rootName, @Nullable DenseExpressionDto filter,
                               DenseExpressionDto measure, String kind, long n,
                               List<FiredCheck> fired, RormToolContext ctx,
                               List<String> unavailable, Map<String, Object> rawMetrics) {
        if (!shapeEligible(kind) || n < 4) {
            return;
        }
        var sampleQuery = new com.rorm.dto.dense.DenseQueryDto(rootName, "t",
            com.rorm.dto.dense.DenseSelectorDto.multi(
                new java.util.LinkedHashSet<>(List.of(
                    new com.rorm.dto.dense.DenseQueryDto.SelectedExpressionDto(measure, "v")
                )),
                false),
            null,
            sampleNormalized(filter, measure),
            null, null, null, (long) SHAPE_SAMPLE_LIMIT, null);
        var rows = executor.execute(sampleQuery, ctx);
        var values = rows.stream()
            .map(r -> r.get("v"))
            .filter(Number.class::isInstance)
            .map(v -> ((Number) v).doubleValue())
            .toList();
        if (values.size() < 4) {
            return;
        }
        try {
            var shape = statsService.distributionShape(values);
            rawMetrics.put("shape", Map.of(
                "skew", shape.skew(),
                "excess_kurtosis", shape.excessKurtosis(),
                "dip_p", shape.dipP() == null ? -1.0 : shape.dipP(),
                "is_multimodal", shape.isMultimodal(),
                "mode_count", shape.modeCount()));
            if (Math.abs(shape.skew()) > 2.0
                || Math.abs(shape.excessKurtosis()) > 3.0
                || (shape.dipP() != null && shape.dipP() < 0.05)
                || shape.isMultimodal()) {
                fired.add(FiredCheck.med("C2_DISTRIBUTIONAL_SHAPE",
                    shapeMessage(shape),
                    Map.of("skew", shape.skew(), "excess_kurtosis", shape.excessKurtosis(),
                        "is_multimodal", shape.isMultimodal(), "mode_count", shape.modeCount())));
            }
        } catch (DescriptiveStatsService.DescriptiveStatsException e) {
            log.warn("Distribution shape check unavailable: {}", e.getMessage());
            unavailable.add("C2_DISTRIBUTIONAL_SHAPE");
        }
    }

    private void evaluateDenominatorStability(String rootName, @Nullable DenseExpressionDto filter,
                                              DenseExpressionDto measure,
                                              @Nullable DenseExpressionDto denominator,
                                              String kind,
                                              List<DenseExpressionDto> axes,
                                              List<FiredCheck> fired,
                                              RormToolContext ctx,
                                              Map<String, Object> rawMetrics) {
        if (!"ratio".equalsIgnoreCase(kind) || denominator == null || axes.isEmpty()) {
            return;
        }
        for (var axis : axes) {
            var query = DescriptiveQueryBuilder.groupedQuery(rootName, axis, filter,
                DescriptiveQueryBuilder.sumOf(denominator, "denom_sum"),
                countStar("n"));
            var rows = executor.execute(query, ctx);
            if (rows.isEmpty()) {
                continue;
            }
            var denomValues = new ArrayList<Double>();
            var segmentsBelowFloor = new ArrayList<String>();
            for (var row : rows) {
                var denomSum = numVal(row, "denom_sum");
                denomValues.add(denomSum);
                if (denomSum < SMALL_N_SEG) {
                    segmentsBelowFloor.add(String.valueOf(row.get("category")));
                }
            }
            var cov = DescriptiveMath.coefficientOfVariation(denomValues);
            rawMetrics.put("denominator_cov", cov);
            if (!segmentsBelowFloor.isEmpty() || cov > RATIO_DENOM_COV_THRESHOLD) {
                fired.add(FiredCheck.med("C5_DENOMINATOR_UNSTABLE",
                    "Ratio denominator varies widely across segments (CoV=" + String.format("%.2f", cov)
                    + ") or has sparse segments — aggregate ratio may be unreliable.",
                    Map.of("cov", cov, "sparse_segments", segmentsBelowFloor)));
                return;
            }
        }
    }

    private void evaluateSmallN(long n, List<DenseExpressionDto> axes, String rootName,
                                @Nullable DenseExpressionDto filter, DenseExpressionDto measure,
                                String kind, @Nullable DenseExpressionDto denominator,
                                List<FiredCheck> fired, RormToolContext ctx) {
        var reasons = new ArrayList<String>();
        if (n < SMALL_N_POP) {
            reasons.add("population N=" + n + " < " + SMALL_N_POP);
        }
        if (!axes.isEmpty()) {
            var fanoutResults = axisFanout.fanout(rootName, filter, measure, kind, denominator, axes, ctx);
            for (var r : fanoutResults) {
                var minSeg = r.segments().stream().mapToLong(AxisFanout.Segment::n).min().orElse(0L);
                if (minSeg > 0 && minSeg < SMALL_N_SEG) {
                    reasons.add("axis has segment with n=" + minSeg + " < " + SMALL_N_SEG);
                    break;
                }
            }
        }
        if (!reasons.isEmpty()) {
            fired.add(FiredCheck.low("C4_SMALL_N",
                "Sample size concern: " + String.join("; ", reasons),
                Map.of("reasons", reasons, "population_n", n)));
        }
    }

    private static String headline(String kind, DenseExpressionDto measure, double value, long n) {
        return String.format("%s(measure) = %.4g (N=%d)", kind.toLowerCase(), value, n);
    }

    private Receipts buildReceipts(String rootName, @Nullable DenseExpressionDto filter,
                                   @Nullable String timeStart, @Nullable String timeEnd,
                                   List<DenseExpressionDto> axes, List<String> unavailable) {
        var window = (timeStart == null && timeEnd == null) ? null
            : (timeStart == null ? "..." : timeStart) + ".." + (timeEnd == null ? "..." : timeEnd);
        var population = filter == null ? rootName : rootName + " (filtered)";
        var axesNames = new ArrayList<String>();
        for (var i = 0; i < axes.size(); i++) {
            axesNames.add("axis_" + i);
        }
        return new Receipts(window, population, axesNames, Archetype.SUMMARY_STAT, unavailable);
    }

    private static boolean outlierEligible(String kind) {
        return switch (kind.toLowerCase()) {
            case "mean", "avg", "average", "total", "sum", "median", "ratio" -> true;
            default -> false;
        };
    }

    private static boolean shapeEligible(String kind) {
        return switch (kind.toLowerCase()) {
            case "mean", "avg", "average", "total", "sum", "median", "ratio" -> true;
            default -> false;
        };
    }

    private static DenseExpressionDto sampleNormalized(@Nullable DenseExpressionDto filter,
                                                       DenseExpressionDto measure) {
        var notNull = DenseExpressionDto.unary("IS_NOT_NULL", measure);
        if (filter == null) {
            return notNull;
        }
        return DenseExpressionDto.binary(filter, "AND", notNull);
    }

    private static String shapeMessage(DescriptiveStatsService.DistributionShape shape) {
        var reasons = new ArrayList<String>();
        if (Math.abs(shape.skew()) > 2.0) {
            reasons.add(String.format("|skew|=%.2f", Math.abs(shape.skew())));
        }
        if (Math.abs(shape.excessKurtosis()) > 3.0) {
            reasons.add(String.format("|excess_kurt|=%.2f", Math.abs(shape.excessKurtosis())));
        }
        if (shape.dipP() != null && shape.dipP() < 0.05) {
            reasons.add(String.format("dip_p=%.3f", shape.dipP()));
        }
        if (shape.isMultimodal()) {
            reasons.add(shape.modeCount() + " modes detected");
        }
        return "Distribution shape fails normal-like assumption: " + String.join(", ", reasons)
               + ". Mean/total may misrepresent the typical value.";
    }
}
