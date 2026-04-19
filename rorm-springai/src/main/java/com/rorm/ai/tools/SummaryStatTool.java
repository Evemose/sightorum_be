package com.rorm.ai.tools;

import com.rorm.ai.JournaledTool;
import com.rorm.ai.RormToolContext;
import com.rorm.ai.tools.DescriptiveDigest.Archetype;
import com.rorm.ai.tools.DescriptiveDigest.FiredCheck;
import com.rorm.ai.tools.DescriptiveDigest.Receipts;
import com.rorm.ai.tools.DescriptiveDigest.Severity;
import com.rorm.ai.tools.DescriptiveToolSupport.CheckCollector;
import com.rorm.dto.dense.DenseExpressionDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.function.BiConsumer;

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
    private static final double CONCENTRATION_THRESHOLD = 0.80;

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
        C7 Concentration — top 3 segments carry >80% of influence (Pareto-like concentration)
        
        INFLUENCE DECOMPOSITION — when decompose_influence=true, computes per-segment contribution
        to the aggregate. Pure arithmetic, not a causal claim. If decomposition_axes is null/empty,
        uses top axis from C1 heterogeneity fanout.
        
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
        @ToolParam(description = "Enable influence decomposition to show per-segment contribution.")
        boolean decomposeInfluence,
        @ToolParam(description = "Axes for influence decomposition. If null/empty and decomposeInfluence=true, uses top axis from C1.")
        @Nullable List<DenseExpressionDto> decompositionAxes,
        ToolContext toolContext
    ) {
        var spec = new SummaryStatSpec(rootName, measure, kind, denominator, filter,
            timeExpression, timeStart, timeEnd, candidateAxes, survivorshipFlag,
            decomposeInfluence, decompositionAxes);
        return execute(spec, RormToolContext.from(toolContext));
    }

    private String execute(SummaryStatSpec spec, RormToolContext ctx) {
        try {
            validateKind(spec.kind);
            var qCtx = new QueryContext(spec, ctx);
            var baseMetrics = computeBaseMetrics(qCtx);
            var checkCtx = new CheckContext(qCtx, baseMetrics);

            runChecks(checkCtx);

            if (spec.decomposeInfluence) {
                new InfluenceDecomposer(this, checkCtx).decompose();
            }

            var headline = headline(spec.kind, spec.measure, baseMetrics.value, baseMetrics.n);
            var receipts = buildReceipts(qCtx, checkCtx.unavailable);
            return formatter.format(Archetype.SUMMARY_STAT, headline,
                checkCtx.fired, receipts, checkCtx.rawMetrics);
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

    private BaseMetrics computeBaseMetrics(QueryContext qCtx) {
        var baseRow = executor.executeSingle(plainQuery(qCtx.spec.rootName, qCtx.effectiveFilter,
            measureSelector(qCtx.spec.measure, qCtx.spec.kind, qCtx.spec.denominator, "value"),
            countStar("n")
        ), qCtx.ctx);
        var value = numVal(baseRow, "value");
        var n = longVal(baseRow, "n");

        List<AxisFanout.AxisResult> fanoutResults = null;
        if (!qCtx.axes.isEmpty()) {
            fanoutResults = axisFanout.fanout(qCtx.spec.rootName, qCtx.effectiveFilter,
                qCtx.spec.measure, qCtx.spec.kind, qCtx.spec.denominator, qCtx.axes, qCtx.ctx);
        }

        return new BaseMetrics(value, n, fanoutResults);
    }

    private void runChecks(CheckContext checkCtx) {
        List<BiConsumer<CheckContext, CheckCollector>> checks = List.of(
            this::checkHeterogeneity,
            this::checkOutlierSensitivity,
            this::checkShape,
            this::checkDenominatorStability,
            this::checkSmallN,
            this::checkSurvivorship
        );

        var collector = new CheckCollector(checkCtx);
        checks.forEach(check -> check.accept(checkCtx, collector));
    }

    private Receipts buildReceipts(QueryContext qCtx, List<String> unavailable) {
        var window = (qCtx.spec.timeStart == null && qCtx.spec.timeEnd == null) ? null
            : (qCtx.spec.timeStart == null ? "..." : qCtx.spec.timeStart) + ".."
              + (qCtx.spec.timeEnd == null ? "..." : qCtx.spec.timeEnd);
        var population = qCtx.effectiveFilter == null ? qCtx.spec.rootName : qCtx.spec.rootName + " (filtered)";
        var axesNames = new ArrayList<String>();
        for (var i = 0; i < qCtx.axes.size(); i++) {
            axesNames.add("axis_" + i);
        }
        return new Receipts(window, population, axesNames, Archetype.SUMMARY_STAT, unavailable);
    }

    private void checkHeterogeneity(CheckContext ctx, CheckCollector collector) {
        if (ctx.qCtx.axes.isEmpty() || ctx.metrics.fanoutResults == null) {
            return;
        }

        var perAxisSnapshots = new ArrayList<Map<String, Object>>();
        var rankings = new ArrayList<Map.Entry<Integer, Double>>();

        for (var i = 0; i < ctx.metrics.fanoutResults.size(); i++) {
            var r = ctx.metrics.fanoutResults.get(i);
            if (r.segments().size() < 2) {
                continue;
            }

            var values = r.segments().stream().map(AxisFanout.Segment::value).toList();
            var hetero = DescriptiveMath.heterogeneity(values, ctx.metrics.value);
            perAxisSnapshots.add(Map.of(
                "axis_index", i, "segments", r.segments().size(),
                "max_min_ratio", hetero.maxMinRatio(),
                "max_deviation_factor", hetero.maxDeviationFactor(),
                "fired", hetero.fired()));

            if (hetero.fired()) {
                rankings.add(Map.entry(i, Math.max(hetero.maxMinRatio(), hetero.maxDeviationFactor())));
            }
        }

        ctx.rawMetrics.put("heterogeneity_per_axis", perAxisSnapshots);
        if (rankings.isEmpty()) {
            return;
        }

        rankings.sort(Map.Entry.<Integer, Double>comparingByValue().reversed());
        var top = rankings.stream().limit(2).map(Map.Entry::getKey).toList();
        var payloadSegments = top.stream()
            .map(idx -> buildAxisPayload(idx, ctx.metrics.fanoutResults.get(idx)))
            .toList();

        collector.fire("C1_HETEROGENEITY", Severity.HIGH,
            "Aggregate hides strong spread across " + top.size() + " candidate axis/axes — segment values diverge >3x or max deviation > 2x median.",
            Map.of("top_axes", payloadSegments));
    }

    private void checkOutlierSensitivity(CheckContext ctx, CheckCollector collector) {
        if (!outlierEligible(ctx.qCtx.spec.kind)) {
            return;
        }

        var boundsRow = executor.executeSingle(plainQuery(ctx.qCtx.spec.rootName, ctx.qCtx.effectiveFilter,
            percentileCont(0.01, ctx.qCtx.spec.measure, "p01"),
            percentileCont(0.99, ctx.qCtx.spec.measure, "p99")
        ), ctx.qCtx.ctx);

        var low = DenseExpressionDto.literal(numVal(boundsRow, "p01"));
        var high = DenseExpressionDto.literal(numVal(boundsRow, "p99"));
        var trimmedFilter = buildTrimmedFilter(ctx.qCtx.effectiveFilter, ctx.qCtx.spec.measure, low, high);

        var trimmedRow = executor.executeSingle(plainQuery(ctx.qCtx.spec.rootName, trimmedFilter,
            measureSelector(ctx.qCtx.spec.measure, ctx.qCtx.spec.kind, ctx.qCtx.spec.denominator, "value"),
            countStar("n")
        ), ctx.qCtx.ctx);

        var trimmedValue = numVal(trimmedRow, "value");
        var absOriginal = Math.abs(ctx.metrics.value);
        if (absOriginal < 1e-12) {
            return;
        }

        var ratio = Math.abs(trimmedValue - ctx.metrics.value) / absOriginal;
        ctx.rawMetrics.put("outlier_trimmed_value", trimmedValue);
        ctx.rawMetrics.put("outlier_ratio", ratio);

        if (ratio > OUTLIER_SENSITIVITY_THRESHOLD) {
            collector.fire("C3_OUTLIER_SENSITIVE", Severity.MED,
                "Value shifts " + String.format("%.1f%%", ratio * 100) + " when top/bottom 1% is trimmed — measure is outlier-driven.",
                Map.of("original", ctx.metrics.value, "trimmed", trimmedValue, "shift_ratio", ratio));
        }
    }

    private void checkShape(CheckContext ctx, CheckCollector collector) {
        if (!shapeEligible(ctx.qCtx.spec.kind) || ctx.metrics.n < 4) {
            return;
        }

        var sampleQuery = new com.rorm.dto.dense.DenseQueryDto(ctx.qCtx.spec.rootName, "t",
            com.rorm.dto.dense.DenseSelectorDto.multi(
                new java.util.LinkedHashSet<>(List.of(
                    new com.rorm.dto.dense.DenseQueryDto.SelectedExpressionDto(ctx.qCtx.spec.measure, "v")
                )), false),
            null, sampleNormalized(ctx.qCtx.effectiveFilter, ctx.qCtx.spec.measure),
            null, null, null, (long) SHAPE_SAMPLE_LIMIT, null);

        var rows = executor.execute(sampleQuery, ctx.qCtx.ctx);
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
            ctx.rawMetrics.put("shape", Map.of(
                "skew", shape.skew(), "excess_kurtosis", shape.excessKurtosis(),
                "dip_p", shape.dipP() == null ? -1.0 : shape.dipP(),
                "is_multimodal", shape.isMultimodal(), "mode_count", shape.modeCount()));

            if (Math.abs(shape.skew()) > 2.0 || Math.abs(shape.excessKurtosis()) > 3.0
                || (shape.dipP() != null && shape.dipP() < 0.05) || shape.isMultimodal()) {
                collector.fire("C2_DISTRIBUTIONAL_SHAPE", Severity.MED,
                    shapeMessage(shape),
                    Map.of("skew", shape.skew(), "excess_kurtosis", shape.excessKurtosis(),
                        "is_multimodal", shape.isMultimodal(), "mode_count", shape.modeCount()));
            }
        } catch (DescriptiveStatsService.DescriptiveStatsException e) {
            log.warn("Distribution shape check unavailable: {}", e.getMessage());
            ctx.unavailable.add("C2_DISTRIBUTIONAL_SHAPE");
        }
    }

    private void checkDenominatorStability(CheckContext ctx, CheckCollector collector) {
        if (!"ratio".equalsIgnoreCase(ctx.qCtx.spec.kind) || ctx.qCtx.spec.denominator == null
            || ctx.qCtx.axes.isEmpty()) {
            return;
        }

        for (var axis : ctx.qCtx.axes) {
            var query = groupedQuery(ctx.qCtx.spec.rootName, axis, ctx.qCtx.effectiveFilter,
                sumOf(ctx.qCtx.spec.denominator, "denom_sum"), countStar("n"));
            var rows = executor.execute(query, ctx.qCtx.ctx);
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
            ctx.rawMetrics.put("denominator_cov", cov);

            if (!segmentsBelowFloor.isEmpty() || cov > RATIO_DENOM_COV_THRESHOLD) {
                collector.fire("C5_DENOMINATOR_UNSTABLE", Severity.MED,
                    "Ratio denominator varies widely across segments (CoV=" + String.format("%.2f", cov)
                    + ") or has sparse segments — aggregate ratio may be unreliable.",
                    Map.of("cov", cov, "sparse_segments", segmentsBelowFloor));
                return;
            }
        }
    }

    private void checkSmallN(CheckContext ctx, CheckCollector collector) {
        var reasons = new ArrayList<String>();
        if (ctx.metrics.n < SMALL_N_POP) {
            reasons.add("population N=" + ctx.metrics.n + " < " + SMALL_N_POP);
        }

        if (!ctx.qCtx.axes.isEmpty() && ctx.metrics.fanoutResults != null) {
            for (var r : ctx.metrics.fanoutResults) {
                var minSeg = r.segments().stream().mapToLong(AxisFanout.Segment::n).min().orElse(0L);
                if (minSeg > 0 && minSeg < SMALL_N_SEG) {
                    reasons.add("axis has segment with n=" + minSeg + " < " + SMALL_N_SEG);
                    break;
                }
            }
        }

        if (!reasons.isEmpty()) {
            collector.fire("C4_SMALL_N", Severity.LOW,
                "Sample size concern: " + String.join("; ", reasons),
                Map.of("reasons", reasons, "population_n", ctx.metrics.n));
        }
    }

    private static String headline(String kind, DenseExpressionDto measure, double value, long n) {
        return String.format("%s(measure) = %.4g (N=%d)", kind.toLowerCase(), value, n);
    }

    private void checkSurvivorship(CheckContext ctx, CheckCollector collector) {
        if (ctx.qCtx.spec.survivorshipFlag) {
            collector.fire("C6_SURVIVORSHIP", Severity.LOW,
                "Population was flagged as filtered to current-only entities — results exclude churned/dead entities.",
                Map.of("survivorship_flag", true));
        }
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

    private static Map<String, Object> buildAxisPayload(int axisIdx, AxisFanout.AxisResult result) {
        var segList = result.segments().stream()
            .map(s -> Map.<String, Object>of("segment", s.key(), "value", s.value(), "n", s.n()))
            .toList();
        return Map.of("axis_index", axisIdx, "segments", segList);
    }

    private static DenseExpressionDto buildTrimmedFilter(@Nullable DenseExpressionDto filter,
                                                         DenseExpressionDto measure,
                                                         DenseExpressionDto low,
                                                         DenseExpressionDto high) {
        var between = between(measure, low, high);
        return filter == null ? between : DenseExpressionDto.binary(filter, "AND", between);
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

    private static DenseExpressionDto sampleNormalized(@Nullable DenseExpressionDto filter,
                                                       DenseExpressionDto measure) {
        var notNull = DenseExpressionDto.unary("IS_NOT_NULL", measure);
        return filter == null ? notNull : DenseExpressionDto.binary(filter, "AND", notNull);
    }

    // Parameter objects
    record SummaryStatSpec(String rootName, DenseExpressionDto measure, String kind,
                           @Nullable DenseExpressionDto denominator, @Nullable DenseExpressionDto filter,
                           @Nullable DenseExpressionDto timeExpression, @Nullable String timeStart,
                           @Nullable String timeEnd, List<DenseExpressionDto> candidateAxes,
                           boolean survivorshipFlag, boolean decomposeInfluence,
                           @Nullable List<DenseExpressionDto> decompositionAxes) {}

    record QueryContext(SummaryStatSpec spec, RormToolContext ctx,
                        DenseExpressionDto effectiveFilter, List<DenseExpressionDto> axes) {
        QueryContext(SummaryStatSpec spec, RormToolContext ctx) {
            this(spec, ctx,
                withTimeFilter(spec.filter, spec.timeExpression, spec.timeStart, spec.timeEnd),
                spec.candidateAxes == null ? List.of() : spec.candidateAxes);
        }
    }

    record BaseMetrics(double value, long n, @Nullable List<AxisFanout.AxisResult> fanoutResults) {}

    static class CheckContext {
        final QueryContext qCtx;
        final BaseMetrics metrics;
        final Map<String, Object> rawMetrics = new LinkedHashMap<>();
        final List<FiredCheck> fired = new ArrayList<>();
        final List<String> unavailable = new ArrayList<>();

        CheckContext(QueryContext qCtx, BaseMetrics metrics) {
            this.qCtx = qCtx;
            this.metrics = metrics;
            rawMetrics.put("value", metrics.value);
            rawMetrics.put("n", metrics.n);
            rawMetrics.put("kind", qCtx.spec.kind);
        }
    }

    static class CheckCollector {
        private final CheckContext ctx;

        CheckCollector(CheckContext ctx) {
            this.ctx = ctx;
        }

        void fire(String code, Severity severity, String message, Map<String, Object> payload) {
            ctx.fired.add(switch (severity) {
                case HIGH -> FiredCheck.high(code, message, payload);
                case MED -> FiredCheck.med(code, message, payload);
                case LOW -> FiredCheck.low(code, message, payload);
            });
        }
    }

    // Influence decomposition extracted to separate class
    @RequiredArgsConstructor
    private static class InfluenceDecomposer {
        private final SummaryStatTool tool;
        private final CheckContext checkCtx;

        void decompose() {
            var decompositionAxis = selectAxis();
            if (decompositionAxis == null) {
                return;
            }

            var fanout = getFanout(decompositionAxis);
            if (fanout == null || fanout.segments().isEmpty()) {
                return;
            }

            var method = determineMethod();
            var segments = computeInfluence(fanout.segments(), method);

            evaluateConcentration(segments);
            buildInfluenceBlock(decompositionAxis.axisIndex, method, segments);
        }

        private SelectedAxis selectAxis() {
            var spec = checkCtx.qCtx.spec;

            if (spec.decompositionAxes != null && !spec.decompositionAxes.isEmpty()) {
                var axis = spec.decompositionAxes.get(0);
                var idx = checkCtx.qCtx.axes.indexOf(axis);
                return new SelectedAxis(axis, idx);
            }

            if (checkCtx.metrics.fanoutResults != null && !checkCtx.metrics.fanoutResults.isEmpty()) {
                var rankings = new ArrayList<Map.Entry<Integer, Double>>();
                for (var i = 0; i < checkCtx.metrics.fanoutResults.size(); i++) {
                    var r = checkCtx.metrics.fanoutResults.get(i);
                    if (r.segments().size() < 2) {
                        continue;
                    }
                    var values = r.segments().stream().map(AxisFanout.Segment::value).toList();
                    var hetero = DescriptiveMath.heterogeneity(values, checkCtx.metrics.value);
                    if (hetero.fired()) {
                        rankings.add(Map.entry(i, Math.max(hetero.maxMinRatio(), hetero.maxDeviationFactor())));
                    }
                }
                if (!rankings.isEmpty()) {
                    rankings.sort(Map.Entry.<Integer, Double>comparingByValue().reversed());
                    var idx = rankings.get(0).getKey();
                    return new SelectedAxis(checkCtx.qCtx.axes.get(idx), idx);
                }
            }
            return null;
        }

        private AxisFanout.AxisResult getFanout(SelectedAxis selected) {
            if (selected.axisIndex >= 0 && checkCtx.metrics.fanoutResults != null
                && selected.axisIndex < checkCtx.metrics.fanoutResults.size()) {
                return checkCtx.metrics.fanoutResults.get(selected.axisIndex);
            }

            var results = tool.axisFanout.fanout(
                checkCtx.qCtx.spec.rootName, checkCtx.qCtx.effectiveFilter,
                checkCtx.qCtx.spec.measure, checkCtx.qCtx.spec.kind,
                checkCtx.qCtx.spec.denominator, List.of(selected.axis), checkCtx.qCtx.ctx);
            return results.isEmpty() ? null : results.get(0);
        }

        private String determineMethod() {
            return switch (checkCtx.qCtx.spec.kind.toLowerCase()) {
                case "total", "sum", "count" -> "additive";
                case "mean", "avg", "average", "ratio" -> "weighted";
                case "median" -> "leave_one_out";
                default -> "additive";
            };
        }

        private List<InfluenceSegment> computeInfluence(List<AxisFanout.Segment> segments, String method) {
            return switch (method) {
                case "additive" -> computeAdditive(segments);
                case "weighted" -> computeWeighted(segments);
                case "leave_one_out" -> computeLeaveOneOut(segments);
                default -> List.of();
            };
        }

        private void evaluateConcentration(List<InfluenceSegment> segments) {
            if (segments.size() < 4) {
                return;
            }

            var sorted = new ArrayList<>(segments);
            sorted.sort((a, b) -> {
                var aInfluence = a.sharePct != null ? Math.abs(a.sharePct)
                    : (a.signedDelta != null ? Math.abs(a.signedDelta) : 0.0);
                var bInfluence = b.sharePct != null ? Math.abs(b.sharePct)
                    : (b.signedDelta != null ? Math.abs(b.signedDelta) : 0.0);
                return Double.compare(bInfluence, aInfluence);
            });

            var top3 = sorted.stream().limit(3).toList();
            var top3Cumulative = top3.stream()
                .mapToDouble(seg -> seg.sharePct != null ? Math.abs(seg.sharePct) : 0.0)
                .sum();

            if (top3.get(0).sharePct != null && top3Cumulative > CONCENTRATION_THRESHOLD * 100) {
                var longTailCount = segments.size() - 3;
                var longTailShare = 100.0 - top3Cumulative;

                checkCtx.fired.add(FiredCheck.med("C7_CONCENTRATION",
                    String.format("Top 3 segments carry %.1f%% of influence — aggregate is Pareto-concentrated.",
                        top3Cumulative),
                    Map.of(
                        "top_3_segments", top3.stream()
                            .map(seg -> Map.of("key", seg.key, "share_pct", seg.sharePct))
                            .toList(),
                        "top_3_cumulative_share", top3Cumulative,
                        "long_tail_residual", longTailShare,
                        "long_tail_segment_count", longTailCount
                    )));
            }
        }

        private void buildInfluenceBlock(int axisIndex, String method, List<InfluenceSegment> segments) {
            var influenceBlock = new LinkedHashMap<String, Object>();
            influenceBlock.put("axis_index", axisIndex >= 0 ? axisIndex : "explicit");
            influenceBlock.put("method", method);
            influenceBlock.put("segments", segments.stream()
                .map(seg -> {
                    var m = new LinkedHashMap<String, Object>();
                    m.put("key", seg.key);
                    m.put("value", seg.value);
                    m.put("n", seg.n);
                    if (seg.sharePct != null) {
                        m.put("share_pct", seg.sharePct);
                    }
                    if (seg.signedDelta != null) {
                        m.put("signed_delta", seg.signedDelta);
                    }
                    if (seg.sign != null) {
                        m.put("sign", seg.sign);
                    }
                    return m;
                })
                .toList());

            if (checkCtx.qCtx.spec.survivorshipFlag) {
                influenceBlock.put("note", "Influence computed on surviving-only aggregate");
            }

            checkCtx.rawMetrics.put("influence", influenceBlock);
        }

        private List<InfluenceSegment> computeAdditive(List<AxisFanout.Segment> segments) {
            var aggregateValue = checkCtx.metrics.value;
            return segments.stream()
                .map(seg -> {
                    var share = Math.abs(aggregateValue) < 1e-12 ? 0.0
                        : (seg.value() / aggregateValue) * 100.0;
                    return new InfluenceSegment(seg.key(), seg.value(), seg.n(), share, null, null);
                })
                .toList();
        }

        private List<InfluenceSegment> computeWeighted(List<AxisFanout.Segment> segments) {
            var aggregateValue = checkCtx.metrics.value;
            var totalN = checkCtx.metrics.n;
            return segments.stream()
                .map(seg -> {
                    double contribution = seg.value() * seg.n();
                    double totalContribution = aggregateValue * totalN;
                    var share = Math.abs(totalContribution) < 1e-12 ? 0.0
                        : (contribution / totalContribution) * 100.0;
                    return new InfluenceSegment(seg.key(), seg.value(), seg.n(), share, null, null);
                })
                .toList();
        }

        private List<InfluenceSegment> computeLeaveOneOut(List<AxisFanout.Segment> segments) {
            // This requires axis reference - needs more context, simplified for now
            return segments.stream()
                .map(seg -> new InfluenceSegment(seg.key(), seg.value(), seg.n(), null, 0.0, "neutral"))
                .toList();
        }

        record SelectedAxis(DenseExpressionDto axis, int axisIndex) {}
    }

    record InfluenceSegment(String key, double value, long n,
                            @Nullable Double sharePct, @Nullable Double signedDelta,
                            @Nullable String sign) {}
}
