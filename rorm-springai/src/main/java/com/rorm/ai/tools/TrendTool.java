package com.rorm.ai.tools;

import com.rorm.ai.JournaledTool;
import com.rorm.ai.RormToolContext;
import com.rorm.ai.tools.DescriptiveDigest.Archetype;
import com.rorm.ai.tools.DescriptiveDigest.FiredCheck;
import com.rorm.ai.tools.DescriptiveDigest.Receipts;
import com.rorm.ai.tools.DescriptiveToolSupport.CheckCollector;
import com.rorm.dto.dense.DenseExpressionDto;
import com.rorm.dto.dense.DenseQueryDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
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
public class TrendTool {

    private static final String EXPR_HINT =
        "Expression (path for column, or derived). Paths resolve against the FROM root.";
    private static final double WINDOW_SENSITIVITY_THRESHOLD = 0.50;
    private static final int SMALL_N_TAIL = 10;
    private static final double VARIANCE_SCALING_THRESHOLD = 0.70;

    private static final ScopedValue<TrendContext> TREND_CTX = ScopedValue.newInstance();

    private final VerificationQueryExecutor executor;
    private final AxisFanout axisFanout;
    private final DescriptiveStatsService statsService;
    private final DescriptiveResponseFormatter formatter;

    @Tool(name = "trendSeries", description = """
        TREND — Series of measure-per-time-bucket plus deterministic structural checks:
        
        T1 Trend vs seasonality strength (STL) — is F_S > F_T?
        T2 Cyclic-window (Python autocorr) — is the window < 2x the detected cycle?
        T3 Window sensitivity — does the slope flip or change magnitude > 50% when the window shifts by 1 grain?
        T4 Structural break (Python PELT) — is there a regime change inside the window?
        T5 Compositional shift — does per-segment direction flip the aggregate trend?
        T6 Small-N tail — are the last 2 buckets below floor?
        T7 Additive-vs-multiplicative — does rolling variance correlate with level?
        
        Returns a JSON digest with fired checks (top 3 by severity) and a receipts block.""")
    public String trendSeries(
        @ToolParam(description = "Root entity name") String rootName,
        @ToolParam(description = "Measure expression to aggregate per bucket. " + EXPR_HINT)
        DenseExpressionDto measure,
        @ToolParam(description = "Measure kind: total|mean|count|ratio|median") String kind,
        @ToolParam(description = "Denominator for kind=ratio. " + EXPR_HINT) @Nullable DenseExpressionDto denominator,
        @ToolParam(description = "Time expression (DATE/TIMESTAMP column). " + EXPR_HINT)
        DenseExpressionDto timeExpression,
        @ToolParam(description = "Bucket grain: day|week|month|quarter|year") String bucketGrain,
        @ToolParam(description = "Window start (ISO).") @Nullable String timeStart,
        @ToolParam(description = "Window end (ISO, exclusive).") @Nullable String timeEnd,
        @ToolParam(description = "Optional WHERE filter. " + EXPR_HINT) @Nullable DenseExpressionDto filter,
        @ToolParam(description = "Seasonal period hint in buckets (for STL). Optional.")
        @Nullable Integer seasonalPeriodHint,
        @ToolParam(description = "Candidate axes for T5 compositional-shift check.")
        List<DenseExpressionDto> candidateAxes,
        @ToolParam(description = "True when population is filtered to current-only entities.")
        boolean survivorshipFlag,
        ToolContext toolContext
    ) {
        var spec = new TrendSpec(rootName, measure, kind, denominator, timeExpression, bucketGrain,
            timeStart, timeEnd, filter, seasonalPeriodHint, candidateAxes, survivorshipFlag);
        return ScopedValue.where(TREND_CTX, new TrendContext(spec, RormToolContext.from(toolContext)))
            .call(this::executeAnalysis);
    }

    private String executeAnalysis() {
        try {
            var spec = TREND_CTX.get().spec;
            validateGrain(spec.bucketGrain);

            var series = fetchBucketedSeries();
            if (series.isEmpty()) {
                return formatter.error(Archetype.TREND, "Trend query returned no buckets");
            }

            var data = SeriesData.from(series);
            var fit = DescriptiveMath.linearRegression(data.values);
            var collector = new CheckCollector();

            buildBasicMetrics(data, fit, collector.rawMetrics);
            runAllChecks(data, fit, collector);

            return formatResponse(data, fit, collector);
        } catch (IllegalArgumentException e) {
            log.warn("Trend invalid input: {}", e.getMessage());
            return formatter.error(Archetype.TREND, e.getMessage());
        } catch (Exception e) {
            log.error("Trend failed", e);
            return formatter.error(Archetype.TREND, "Trend failed: " + e.getMessage());
        }
    }

    private void validateGrain(String bucketGrain) {
        if (!knownGrains().contains(bucketGrain.toLowerCase())) {
            throw new IllegalArgumentException("Unknown bucketGrain: " + bucketGrain
                                               + " (expected day|week|month|quarter|year)");
        }
    }

    private List<BucketPoint> fetchBucketedSeries() {
        var ctx = TREND_CTX.get();
        var spec = ctx.spec;
        var bucketExpr = dateTruncBucket(spec.bucketGrain, spec.timeExpression);
        var measureSel = measureSelector(spec.measure, spec.kind, spec.denominator, "value");

        var querySelections = new java.util.LinkedHashSet<DenseQueryDto.SelectedExpressionDto>();
        querySelections.add(new DenseQueryDto.SelectedExpressionDto(bucketExpr, "bucket"));
        querySelections.add(measureSel);
        querySelections.add(DescriptiveQueryBuilder.countStar("n"));

        var query = new DenseQueryDto(
            spec.rootName, "t",
            com.rorm.dto.dense.DenseSelectorDto.multi(querySelections, false),
            null, ctx.effectiveFilter,
            new DenseQueryDto.GroupByDto(List.of(bucketExpr)),
            null,
            List.of(new DenseQueryDto.OrderByDto(bucketExpr, true)),
            null, null);

        return executor.execute(query, ctx.toolCtx).stream()
            .map(row -> new BucketPoint(
                String.valueOf(row.get("bucket")),
                numVal(row, "value"),
                longVal(row, "n")))
            .toList();
    }

    private void buildBasicMetrics(SeriesData data, DescriptiveMath.LinearFit fit,
                                   Map<String, Object> rawMetrics) {
        rawMetrics.put("series_length", data.points.size());
        rawMetrics.put("slope", fit.slope());
        rawMetrics.put("intercept", fit.intercept());
        rawMetrics.put("r_squared", fit.rSquared());
    }

    private void runAllChecks(SeriesData data, DescriptiveMath.LinearFit fit, CheckCollector collector) {
        evaluatePythonSeriesChecks(data.values, collector);
        evaluateWindowSensitivity(fit.slope(), collector);
        evaluateCompositionalShift(fit.slope(), collector);
        evaluateSmallNTail(data.counts, collector.fired);
        evaluateVarianceScaling(data.values, collector);
        evaluateSurvivorship(collector.fired);
    }

    private String formatResponse(SeriesData data, DescriptiveMath.LinearFit fit,
                                  CheckCollector collector) {
        var spec = TREND_CTX.get().spec;
        var axes = TREND_CTX.get().axes;
        var effectiveFilter = TREND_CTX.get().effectiveFilter;

        var slopeStr = Math.abs(fit.slope()) < 1e-9 ? "flat"
            : fit.slope() > 0 ? "up " + String.format("%.4g/bucket", fit.slope())
            : "down " + String.format("%.4g/bucket", Math.abs(fit.slope()));
        var headline = String.format("%s series %s over %d %s buckets",
            spec.kind, slopeStr, data.points.size(), spec.bucketGrain);
        var receipts = new Receipts(
            (spec.timeStart == null ? "..." : spec.timeStart) + ".."
            + (spec.timeEnd == null ? "..." : spec.timeEnd),
            effectiveFilter == null ? spec.rootName : spec.rootName + " (filtered)",
            axesNames(axes), Archetype.TREND, collector.unavailable);
        return formatter.format(Archetype.TREND, headline, collector.fired, receipts,
            collector.rawMetrics);
    }

    private void evaluatePythonSeriesChecks(double[] values, CheckCollector collector) {
        var spec = TREND_CTX.get().spec;
        if (values.length < 4) {
            return;
        }
        var valuesList = new ArrayList<Double>(values.length);
        for (var v : values) {
            valuesList.add(v);
        }
        var periodHint = spec.seasonalPeriodHint;
        var runStl = periodHint != null && periodHint >= 2 && values.length >= 2 * periodHint;
        try {
            var analysis = statsService.seriesAnalysis(valuesList, periodHint, runStl, true, true, 5.0);
            processStlResults(analysis, collector);
            processAutocorrResults(analysis, values.length, collector);
            processChangepoints(analysis, collector);
        } catch (DescriptiveStatsService.DescriptiveStatsException e) {
            log.warn("Series analysis unavailable: {}", e.getMessage());
            markPythonChecksUnavailable(collector.unavailable);
            runJavaChangePointFallback(values, collector);
        }
    }

    private void evaluateWindowSensitivity(double baseSlope, CheckCollector collector) {
        var spec = TREND_CTX.get().spec;
        if (spec.timeStart == null && spec.timeEnd == null) {
            return;
        }
        var shiftedFilter = withTimeFilter(spec.filter, spec.timeExpression,
            shiftedStart(spec.timeStart, spec.bucketGrain, -1),
            shiftedEnd(spec.timeEnd, spec.bucketGrain, -1));

        var shiftedCtx = new TrendContext(
            new TrendSpec(spec.rootName, spec.measure, spec.kind, spec.denominator,
                spec.timeExpression, spec.bucketGrain, null, null, shiftedFilter,
                spec.seasonalPeriodHint, spec.candidateAxes, spec.survivorshipFlag),
            TREND_CTX.get().toolCtx);

        var shiftedSeries = ScopedValue.where(TREND_CTX, shiftedCtx)
            .call(this::fetchBucketedSeries);

        if (shiftedSeries.size() < 2) {
            return;
        }

        var shiftedValues = shiftedSeries.stream()
            .mapToDouble(BucketPoint::value)
            .toArray();
        var shiftedFit = DescriptiveMath.linearRegression(shiftedValues);

        checkSlopeSensitivity(baseSlope, shiftedFit.slope(), spec.bucketGrain, collector);
    }

    private void evaluateCompositionalShift(double baseSlope, CheckCollector collector) {
        var ctx = TREND_CTX.get();
        var spec = ctx.spec;
        if (ctx.axes.isEmpty() || Math.abs(baseSlope) < 1e-9) {
            return;
        }
        var fanoutResults = axisFanout.fanoutByBucket(spec.rootName, ctx.effectiveFilter,
            spec.timeExpression, spec.bucketGrain, spec.measure, spec.kind, spec.denominator,
            ctx.axes, ctx.toolCtx);

        var perAxisSnapshots = new ArrayList<Map<String, Object>>();
        for (var i = 0; i < fanoutResults.size(); i++) {
            if (checkAxisForCompositionalShift(fanoutResults.get(i), i, baseSlope,
                perAxisSnapshots, collector.fired)) {
                break;
            }
        }
        collector.rawMetrics.put("compositional_shift_per_axis", perAxisSnapshots);
    }

    private static void evaluateSmallNTail(long[] counts, List<FiredCheck> fired) {
        if (counts.length < 2) {
            return;
        }
        var lastTwoBelow = counts[counts.length - 1] < SMALL_N_TAIL
                           && counts[counts.length - 2] < SMALL_N_TAIL;
        if (lastTwoBelow) {
            fired.add(FiredCheck.low("T6_SMALL_N_TAIL",
                "Most recent 2 buckets have N < " + SMALL_N_TAIL + " — recent points may be unstable.",
                Map.of("tail_counts", List.of(counts[counts.length - 2], counts[counts.length - 1]))));
        }
    }

    private static void evaluateVarianceScaling(double[] values, CheckCollector collector) {
        if (values.length < 6) {
            return;
        }
        var corr = DescriptiveMath.varianceLevelCorrelation(values, 3);
        collector.rawMetrics.put("variance_level_corr", corr);
        if (corr > VARIANCE_SCALING_THRESHOLD) {
            collector.fired.add(FiredCheck.low("T7_MULTIPLICATIVE_VARIANCE",
                String.format("Rolling variance correlates with level (r=%.2f) — series is multiplicative; "
                              + "log scale may be more appropriate.", corr),
                Map.of("correlation", corr)));
        }
    }

    private void evaluateSurvivorship(List<FiredCheck> fired) {
        if (TREND_CTX.get().spec.survivorshipFlag) {
            fired.add(FiredCheck.low("T_SURVIVORSHIP",
                "Population flagged as current-only — trend reflects survivors, not all entities over time.",
                Map.of("survivorship_flag", true)));
        }
    }

    private static List<String> axesNames(List<DenseExpressionDto> axes) {
        var names = new ArrayList<String>();
        for (var i = 0; i < axes.size(); i++) {
            names.add("axis_" + i);
        }
        return names;
    }

    private void processStlResults(DescriptiveStatsService.SeriesAnalysis analysis,
                                   CheckCollector collector) {
        var stl = analysis.stl();
        collector.rawMetrics.put("python_notes", analysis.notes());
        if (stl != null) {
            collector.rawMetrics.put("stl_f_t", stl.fT());
            collector.rawMetrics.put("stl_f_s", stl.fS());
            if (stl.fS() > stl.fT() && stl.fS() > 0.5) {
                collector.fired.add(FiredCheck.med("T1_SEASONALITY_DOMINATES",
                    String.format("Seasonal strength F_S=%.2f exceeds trend strength F_T=%.2f — "
                                  + "apparent trend may be a seasonal phase.", stl.fS(), stl.fT()),
                    Map.of("f_t", stl.fT(), "f_s", stl.fS())));
            }
        }
    }

    private void processAutocorrResults(
        DescriptiveStatsService.SeriesAnalysis analysis,
        int seriesLength, CheckCollector collector
    ) {
        var peak = analysis.autocorrPeak();
        if (peak != null && peak.peakLag() > 0 && seriesLength < 2 * peak.peakLag()) {
            collector.rawMetrics.put("autocorr_peak_lag", peak.peakLag());
            collector.fired.add(FiredCheck.med("T2_CYCLIC_WINDOW",
                String.format("Window length %d is less than 2x detected cycle length %d (peak autocorr=%.2f) — "
                              + "the 'trend' may be a cycle phase.", seriesLength, peak.peakLag(), peak.peakValue()),
                Map.of("window_length", seriesLength, "cycle_length", peak.peakLag(),
                    "peak_value", peak.peakValue())));
        }
    }

    private void processChangepoints(
        DescriptiveStatsService.SeriesAnalysis analysis,
        CheckCollector collector
    ) {
        var breaks = analysis.changepoints();
        if (breaks != null && !breaks.isEmpty()) {
            collector.rawMetrics.put("changepoints", breaks);
            collector.fired.add(FiredCheck.high("T4_STRUCTURAL_BREAK",
                "PELT detected " + breaks.size() + " structural break(s) at bucket indices " + breaks
                + " — the series is not a single regime.",
                Map.of("break_indices", breaks)));
        }
    }

    private void markPythonChecksUnavailable(List<String> unavailable) {
        unavailable.add("T1_SEASONALITY_DOMINATES");
        unavailable.add("T2_CYCLIC_WINDOW");
        unavailable.add("T4_STRUCTURAL_BREAK");
    }

    private void runJavaChangePointFallback(double[] values, CheckCollector collector) {
        var cusum = DescriptiveMath.cusumChangePoint(values);
        collector.rawMetrics.put("cusum_fallback", Map.of(
            "break_index", cusum.breakIndex(),
            "max_deviation", cusum.maxDeviation(),
            "significant", cusum.significant()));
        if (cusum.significant()) {
            collector.fired.add(FiredCheck.high("T4_STRUCTURAL_BREAK_CUSUM",
                "Java CUSUM fallback detected a likely structural break at bucket index " + cusum.breakIndex()
                + " (Python PELT unavailable).",
                Map.of("break_index", cusum.breakIndex(), "max_deviation", cusum.maxDeviation())));
        }
    }

    private static @Nullable String shiftedStart(@Nullable String original, String grain, int delta) {
        return original == null ? null : shiftIso(original, grain, delta);
    }

    private static @Nullable String shiftedEnd(@Nullable String original, String grain, int delta) {
        return original == null ? null : shiftIso(original, grain, delta);
    }

    private void checkSlopeSensitivity(double baseSlope, double shiftedSlope,
                                       String bucketGrain, CheckCollector collector) {
        collector.rawMetrics.put("shifted_slope", shiftedSlope);
        var slopeDirectionFlipped = Math.signum(baseSlope) != Math.signum(shiftedSlope)
                                    && Math.abs(baseSlope) > 1e-9 && Math.abs(shiftedSlope) > 1e-9;
        var magnitudeChange = Math.abs(baseSlope) < 1e-9 ? 0.0
            : Math.abs(shiftedSlope - baseSlope) / Math.abs(baseSlope);
        if (slopeDirectionFlipped || magnitudeChange > WINDOW_SENSITIVITY_THRESHOLD) {
            collector.fired.add(FiredCheck.med("T3_WINDOW_SENSITIVITY",
                String.format("Slope changes %s when the window shifts 1 %s earlier — trend is window-dependent.",
                    slopeDirectionFlipped ? "direction" : String.format("by %.0f%%", magnitudeChange * 100),
                    bucketGrain),
                Map.of("base_slope", baseSlope, "shifted_slope", shiftedSlope,
                    "magnitude_change", magnitudeChange, "direction_flipped", slopeDirectionFlipped)));
        }
    }

    private boolean checkAxisForCompositionalShift(AxisFanout.BucketedAxisResult axisResult,
                                                   int axisIndex, double baseSlope,
                                                   List<Map<String, Object>> snapshots,
                                                   List<FiredCheck> fired) {
        var bucketsByOrder = new ArrayList<>(axisResult.segmentsByBucket().keySet());
        if (bucketsByOrder.size() < 3) {
            return false;
        }

        var perSegmentSlopes = computePerSegmentSlopes(axisResult, bucketsByOrder);
        if (perSegmentSlopes.isEmpty()) {
            return false;
        }

        var directions = perSegmentSlopes.stream()
            .map(s -> DescriptiveMath.direction(s, 1e-9))
            .toList();
        var aggregateDir = DescriptiveMath.direction(baseSlope, 1e-9);
        var majority = DescriptiveMath.directionMajority(directions, aggregateDir);

        snapshots.add(Map.of(
            "axis_index", axisIndex,
            "agree", majority.agree(),
            "disagree", majority.disagree(),
            "flat", majority.flat()));

        if (majority.disagree() > majority.agree()) {
            fired.add(FiredCheck.high("T5_COMPOSITIONAL_SHIFT",
                String.format("Aggregate trend direction (%s) disagrees with majority of per-segment trends on axis %d "
                              + "(%d segments reverse, %d agree). Simpson's paradox in time.",
                    aggregateDir, axisIndex, majority.disagree(), majority.agree()),
                Map.of("axis_index", axisIndex, "disagree", majority.disagree(), "agree", majority.agree())));
            return true;
        }
        return false;
    }

    private static String shiftIso(String iso, String grain, int delta) {
        try {
            var date = java.time.LocalDate.parse(iso.substring(0, Math.min(10, iso.length())));
            var shifted = switch (grain.toLowerCase()) {
                case "day" -> date.plusDays(delta);
                case "week" -> date.plusWeeks(delta);
                case "month" -> date.plusMonths(delta);
                case "quarter" -> date.plusMonths(3L * delta);
                case "year" -> date.plusYears(delta);
                default -> date;
            };
            return shifted.toString();
        } catch (Exception e) {
            return iso;
        }
    }

    private List<Double> computePerSegmentSlopes(AxisFanout.BucketedAxisResult axisResult,
                                                 List<String> bucketsByOrder) {
        var segmentNames = collectSegmentNames(axisResult, bucketsByOrder);
        var perSegmentSlopes = new ArrayList<Double>();

        for (var seg : segmentNames) {
            var segValues = collectSegmentValues(axisResult, bucketsByOrder, seg);
            if (segValues.size() < 3) {
                continue;
            }
            var arr = segValues.stream().mapToDouble(Double::doubleValue).toArray();
            perSegmentSlopes.add(DescriptiveMath.linearRegression(arr).slope());
        }
        return perSegmentSlopes;
    }

    private java.util.Set<String> collectSegmentNames(AxisFanout.BucketedAxisResult axisResult,
                                                      List<String> bucketsByOrder) {
        var segmentNames = new java.util.LinkedHashSet<String>();
        for (var bucket : bucketsByOrder) {
            for (var s : axisResult.segmentsByBucket().get(bucket)) {
                segmentNames.add(s.key());
            }
        }
        return segmentNames;
    }

    private List<Double> collectSegmentValues(AxisFanout.BucketedAxisResult axisResult,
                                              List<String> bucketsByOrder, String seg) {
        var segValues = new ArrayList<Double>();
        for (var bucket : bucketsByOrder) {
            var segmentsInBucket = axisResult.segmentsByBucket().get(bucket);
            var match = segmentsInBucket.stream()
                .filter(s -> s.key().equals(seg))
                .findFirst();
            if (match.isPresent()) {
                segValues.add(match.get().value());
            }
        }
        return segValues;
    }

    record TrendSpec(
        String rootName, DenseExpressionDto measure, String kind,
        @Nullable DenseExpressionDto denominator, DenseExpressionDto timeExpression,
        String bucketGrain, @Nullable String timeStart, @Nullable String timeEnd,
        @Nullable DenseExpressionDto filter, @Nullable Integer seasonalPeriodHint,
        List<DenseExpressionDto> candidateAxes, boolean survivorshipFlag
    ) {}

    record TrendContext(
        TrendSpec spec, RormToolContext toolCtx,
        DenseExpressionDto effectiveFilter, List<DenseExpressionDto> axes
    ) {
        TrendContext(TrendSpec spec, RormToolContext toolCtx) {
            this(spec, toolCtx,
                withTimeFilter(spec.filter, spec.timeExpression, spec.timeStart, spec.timeEnd),
                spec.candidateAxes == null ? List.of() : spec.candidateAxes);
        }
    }

    record SeriesData(List<BucketPoint> points, double[] values, long[] counts) {
        static SeriesData from(List<BucketPoint> points) {
            var values = new double[points.size()];
            var counts = new long[points.size()];
            for (var i = 0; i < points.size(); i++) {
                values[i] = points.get(i).value();
                counts[i] = points.get(i).n();
            }
            return new SeriesData(points, values, counts);
        }
    }


    private record BucketPoint(String bucket, double value, long n) {}
}
