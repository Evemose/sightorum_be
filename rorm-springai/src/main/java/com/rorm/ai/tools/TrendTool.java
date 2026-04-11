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
public class TrendTool {

    private static final String EXPR_HINT =
        "Expression (path for column, or derived). Paths resolve against the FROM root.";

    private static final double WINDOW_SENSITIVITY_THRESHOLD = 0.50;
    private static final int SMALL_N_TAIL = 10;
    private static final double VARIANCE_SCALING_THRESHOLD = 0.70;

    private final VerificationQueryExecutor executor;
    private final AxisFanout axisFanout;
    private final DescriptiveStatsService statsService;
    private final DescriptiveResponseFormatter formatter;

    @Tool(name = "trendSeries", description = """
        TREND — Series of measure-per-time-bucket plus deterministic structural checks:
        
        T1 Trend vs seasonality strength (Python STL) — is F_S > F_T?
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
        try {
            if (!knownGrains().contains(bucketGrain.toLowerCase())) {
                throw new IllegalArgumentException("Unknown bucketGrain: " + bucketGrain
                                                   + " (expected day|week|month|quarter|year)");
            }
            var ctx = RormToolContext.from(toolContext);
            var effectiveFilter = withTimeFilter(filter, timeExpression, timeStart, timeEnd);
            var axes = candidateAxes == null ? List.<DenseExpressionDto>of() : candidateAxes;
            var unavailable = new ArrayList<String>();

            var series = fetchBucketedSeries(rootName, measure, kind, denominator, effectiveFilter,
                timeExpression, bucketGrain, ctx);
            if (series.isEmpty()) {
                return formatter.error(Archetype.TREND, "Trend query returned no buckets");
            }

            var values = new double[series.size()];
            var counts = new long[series.size()];
            for (var i = 0; i < series.size(); i++) {
                values[i] = series.get(i).value();
                counts[i] = series.get(i).n();
            }

            var fit = DescriptiveMath.linearRegression(values);
            var fired = new ArrayList<FiredCheck>();
            var rawMetrics = new LinkedHashMap<String, Object>();
            rawMetrics.put("series_length", series.size());
            rawMetrics.put("slope", fit.slope());
            rawMetrics.put("intercept", fit.intercept());
            rawMetrics.put("r_squared", fit.rSquared());

            evaluatePythonSeriesChecks(values, seasonalPeriodHint, fired, unavailable, rawMetrics);
            evaluateWindowSensitivity(rootName, measure, kind, denominator, filter, timeExpression,
                bucketGrain, timeStart, timeEnd, fit.slope(), fired, ctx, rawMetrics);
            evaluateCompositionalShift(rootName, effectiveFilter, measure, kind, denominator,
                timeExpression, bucketGrain, axes, fit.slope(), fired, ctx, rawMetrics);
            evaluateSmallNTail(series, counts, fired);
            evaluateVarianceScaling(values, fired, rawMetrics);
            if (survivorshipFlag) {
                fired.add(FiredCheck.low("T_SURVIVORSHIP",
                    "Population flagged as current-only — trend reflects survivors, not all entities over time.",
                    Map.of("survivorship_flag", true)));
            }

            var slopeStr = Math.abs(fit.slope()) < 1e-9 ? "flat"
                : fit.slope() > 0 ? "up " + String.format("%.4g/bucket", fit.slope())
                : "down " + String.format("%.4g/bucket", Math.abs(fit.slope()));
            var headline = String.format("%s series %s over %d %s buckets",
                kind, slopeStr, series.size(), bucketGrain);
            var receipts = new Receipts(
                (timeStart == null ? "..." : timeStart) + ".." + (timeEnd == null ? "..." : timeEnd),
                effectiveFilter == null ? rootName : rootName + " (filtered)",
                axesNames(axes), Archetype.TREND, unavailable);
            return formatter.format(Archetype.TREND, headline, fired, receipts, rawMetrics);
        } catch (IllegalArgumentException e) {
            log.warn("Trend invalid input: {}", e.getMessage());
            return formatter.error(Archetype.TREND, e.getMessage());
        } catch (Exception e) {
            log.error("Trend failed", e);
            return formatter.error(Archetype.TREND, "Trend failed: " + e.getMessage());
        }
    }

    private List<BucketPoint> fetchBucketedSeries(String rootName, DenseExpressionDto measure,
                                                  String kind,
                                                  @Nullable DenseExpressionDto denominator,
                                                  @Nullable DenseExpressionDto filter,
                                                  DenseExpressionDto timeExpression,
                                                  String bucketGrain,
                                                  RormToolContext ctx) {
        var bucketExpr = dateTruncBucket(bucketGrain, timeExpression);
        var query = bucketedQuery(rootName, bucketExpr, filter,
            measureSelector(measure, kind, denominator, "value"));
        var querySelections = new java.util.LinkedHashSet<com.rorm.dto.dense.DenseQueryDto.SelectedExpressionDto>();
        querySelections.add(new com.rorm.dto.dense.DenseQueryDto.SelectedExpressionDto(bucketExpr, "bucket"));
        querySelections.add(measureSelector(measure, kind, denominator, "value"));
        querySelections.add(DescriptiveQueryBuilder.countStar("n"));
        var enrichedQuery = new com.rorm.dto.dense.DenseQueryDto(
            rootName, "t",
            com.rorm.dto.dense.DenseSelectorDto.multi(querySelections, false),
            null, query.where(),
            new com.rorm.dto.dense.DenseQueryDto.GroupByDto(List.of(bucketExpr)),
            null,
            List.of(new com.rorm.dto.dense.DenseQueryDto.OrderByDto(bucketExpr, true)),
            null, null);
        var rows = executor.execute(enrichedQuery, ctx);
        var result = new ArrayList<BucketPoint>();
        for (var row : rows) {
            result.add(new BucketPoint(
                String.valueOf(row.get("bucket")),
                numVal(row, "value"),
                longVal(row, "n")
            ));
        }
        return result;
    }

    private void evaluatePythonSeriesChecks(double[] values, @Nullable Integer periodHint,
                                            List<FiredCheck> fired,
                                            List<String> unavailable,
                                            Map<String, Object> rawMetrics) {
        if (values.length < 4) {
            return;
        }
        var valuesList = new ArrayList<Double>(values.length);
        for (var v : values) {
            valuesList.add(v);
        }
        var runStl = periodHint != null && periodHint >= 2 && values.length >= 2 * periodHint;
        try {
            var analysis = statsService.seriesAnalysis(valuesList, periodHint, runStl, true, true, 5.0);
            var stl = analysis.stl();
            var peak = analysis.autocorrPeak();
            var breaks = analysis.changepoints();
            rawMetrics.put("python_notes", analysis.notes());
            if (stl != null) {
                rawMetrics.put("stl_f_t", stl.fT());
                rawMetrics.put("stl_f_s", stl.fS());
                if (stl.fS() > stl.fT() && stl.fS() > 0.5) {
                    fired.add(FiredCheck.med("T1_SEASONALITY_DOMINATES",
                        String.format("Seasonal strength F_S=%.2f exceeds trend strength F_T=%.2f — "
                                      + "apparent trend may be a seasonal phase.", stl.fS(), stl.fT()),
                        Map.of("f_t", stl.fT(), "f_s", stl.fS())));
                }
            }
            if (peak != null && peak.peakLag() > 0 && values.length < 2 * peak.peakLag()) {
                rawMetrics.put("autocorr_peak_lag", peak.peakLag());
                fired.add(FiredCheck.med("T2_CYCLIC_WINDOW",
                    String.format("Window length %d is less than 2x detected cycle length %d (peak autocorr=%.2f) — "
                                  + "the 'trend' may be a cycle phase.", values.length, peak.peakLag(), peak.peakValue()),
                    Map.of("window_length", values.length, "cycle_length", peak.peakLag(),
                        "peak_value", peak.peakValue())));
            }
            if (breaks != null && !breaks.isEmpty()) {
                rawMetrics.put("changepoints", breaks);
                fired.add(FiredCheck.high("T4_STRUCTURAL_BREAK",
                    "PELT detected " + breaks.size() + " structural break(s) at bucket indices " + breaks
                    + " — the series is not a single regime.",
                    Map.of("break_indices", breaks)));
            }
        } catch (DescriptiveStatsService.DescriptiveStatsException e) {
            log.warn("Series analysis unavailable: {}", e.getMessage());
            unavailable.add("T1_SEASONALITY_DOMINATES");
            unavailable.add("T2_CYCLIC_WINDOW");
            unavailable.add("T4_STRUCTURAL_BREAK");
            runJavaChangePointFallback(values, fired, rawMetrics);
        }
    }

    private void evaluateWindowSensitivity(String rootName, DenseExpressionDto measure, String kind,
                                           @Nullable DenseExpressionDto denominator,
                                           @Nullable DenseExpressionDto baseFilter,
                                           DenseExpressionDto timeExpression, String bucketGrain,
                                           @Nullable String timeStart, @Nullable String timeEnd,
                                           double baseSlope, List<FiredCheck> fired,
                                           RormToolContext ctx, Map<String, Object> rawMetrics) {
        if (timeStart == null && timeEnd == null) {
            return;
        }
        var shiftedFilter = withTimeFilter(baseFilter, timeExpression,
            shiftedStart(timeStart, bucketGrain, -1), shiftedEnd(timeEnd, bucketGrain, -1));
        var shiftedSeries = fetchBucketedSeries(rootName, measure, kind, denominator, shiftedFilter,
            timeExpression, bucketGrain, ctx);
        if (shiftedSeries.size() < 2) {
            return;
        }
        var shiftedValues = new double[shiftedSeries.size()];
        for (var i = 0; i < shiftedSeries.size(); i++) {
            shiftedValues[i] = shiftedSeries.get(i).value();
        }
        var shiftedFit = DescriptiveMath.linearRegression(shiftedValues);
        rawMetrics.put("shifted_slope", shiftedFit.slope());
        var slopeDirectionFlipped = Math.signum(baseSlope) != Math.signum(shiftedFit.slope())
                                    && Math.abs(baseSlope) > 1e-9 && Math.abs(shiftedFit.slope()) > 1e-9;
        var magnitudeChange = Math.abs(baseSlope) < 1e-9 ? 0.0
            : Math.abs(shiftedFit.slope() - baseSlope) / Math.abs(baseSlope);
        if (slopeDirectionFlipped || magnitudeChange > WINDOW_SENSITIVITY_THRESHOLD) {
            fired.add(FiredCheck.med("T3_WINDOW_SENSITIVITY",
                String.format("Slope changes %s when the window shifts 1 %s earlier — trend is window-dependent.",
                    slopeDirectionFlipped ? "direction" : String.format("by %.0f%%", magnitudeChange * 100),
                    bucketGrain),
                Map.of("base_slope", baseSlope, "shifted_slope", shiftedFit.slope(),
                    "magnitude_change", magnitudeChange, "direction_flipped", slopeDirectionFlipped)));
        }
    }

    private void evaluateCompositionalShift(String rootName, @Nullable DenseExpressionDto filter,
                                            DenseExpressionDto measure, String kind,
                                            @Nullable DenseExpressionDto denominator,
                                            DenseExpressionDto timeExpression, String bucketGrain,
                                            List<DenseExpressionDto> axes, double baseSlope,
                                            List<FiredCheck> fired, RormToolContext ctx,
                                            Map<String, Object> rawMetrics) {
        if (axes.isEmpty() || Math.abs(baseSlope) < 1e-9) {
            return;
        }
        var fanoutResults = axisFanout.fanoutByBucket(rootName, filter, timeExpression, bucketGrain,
            measure, kind, denominator, axes, ctx);
        var perAxisSnapshots = new ArrayList<Map<String, Object>>();
        for (var i = 0; i < fanoutResults.size(); i++) {
            var axisResult = fanoutResults.get(i);
            var bucketsByOrder = new ArrayList<>(axisResult.segmentsByBucket().keySet());
            if (bucketsByOrder.size() < 3) {
                continue;
            }
            var segmentNames = new java.util.LinkedHashSet<String>();
            for (var bucket : bucketsByOrder) {
                for (var s : axisResult.segmentsByBucket().get(bucket)) {
                    segmentNames.add(s.key());
                }
            }
            var perSegmentSlopes = new ArrayList<Double>();
            for (var seg : segmentNames) {
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
                if (segValues.size() < 3) {
                    continue;
                }
                var arr = new double[segValues.size()];
                for (var j = 0; j < segValues.size(); j++) {
                    arr[j] = segValues.get(j);
                }
                perSegmentSlopes.add(DescriptiveMath.linearRegression(arr).slope());
            }
            if (perSegmentSlopes.isEmpty()) {
                continue;
            }
            var directions = perSegmentSlopes.stream()
                .map(s -> DescriptiveMath.direction(s, 1e-9))
                .toList();
            var aggregateDir = DescriptiveMath.direction(baseSlope, 1e-9);
            var majority = DescriptiveMath.directionMajority(directions, aggregateDir);
            perAxisSnapshots.add(Map.of(
                "axis_index", i,
                "agree", majority.agree(),
                "disagree", majority.disagree(),
                "flat", majority.flat()));
            if (majority.disagree() > majority.agree()) {
                fired.add(FiredCheck.high("T5_COMPOSITIONAL_SHIFT",
                    String.format("Aggregate trend direction (%s) disagrees with majority of per-segment trends on axis %d "
                                  + "(%d segments reverse, %d agree). Simpson's paradox in time.",
                        aggregateDir, i, majority.disagree(), majority.agree()),
                    Map.of("axis_index", i, "disagree", majority.disagree(), "agree", majority.agree())));
                break;
            }
        }
        rawMetrics.put("compositional_shift_per_axis", perAxisSnapshots);
    }

    private static void evaluateSmallNTail(List<BucketPoint> series, long[] counts,
                                           List<FiredCheck> fired) {
        if (counts.length < 2) {
            return;
        }
        var lastTwoBelow = counts[counts.length - 1] < SMALL_N_TAIL && counts[counts.length - 2] < SMALL_N_TAIL;
        if (lastTwoBelow) {
            fired.add(FiredCheck.low("T6_SMALL_N_TAIL",
                "Most recent 2 buckets have N < " + SMALL_N_TAIL + " — recent points may be unstable.",
                Map.of("tail_counts", List.of(counts[counts.length - 2], counts[counts.length - 1]))));
        }
    }

    private static void evaluateVarianceScaling(double[] values, List<FiredCheck> fired,
                                                Map<String, Object> rawMetrics) {
        if (values.length < 6) {
            return;
        }
        var corr = DescriptiveMath.varianceLevelCorrelation(values, 3);
        rawMetrics.put("variance_level_corr", corr);
        if (corr > VARIANCE_SCALING_THRESHOLD) {
            fired.add(FiredCheck.low("T7_MULTIPLICATIVE_VARIANCE",
                String.format("Rolling variance correlates with level (r=%.2f) — series is multiplicative; "
                              + "log scale may be more appropriate.", corr),
                Map.of("correlation", corr)));
        }
    }

    private static List<String> axesNames(List<DenseExpressionDto> axes) {
        var names = new ArrayList<String>();
        for (var i = 0; i < axes.size(); i++) {
            names.add("axis_" + i);
        }
        return names;
    }

    private void runJavaChangePointFallback(double[] values, List<FiredCheck> fired,
                                            Map<String, Object> rawMetrics) {
        var cusum = DescriptiveMath.cusumChangePoint(values);
        rawMetrics.put("cusum_fallback", Map.of(
            "break_index", cusum.breakIndex(),
            "max_deviation", cusum.maxDeviation(),
            "significant", cusum.significant()));
        if (cusum.significant()) {
            fired.add(FiredCheck.high("T4_STRUCTURAL_BREAK_CUSUM",
                "Java CUSUM fallback detected a likely structural break at bucket index " + cusum.breakIndex()
                + " (Python PELT unavailable).",
                Map.of("break_index", cusum.breakIndex(), "max_deviation", cusum.maxDeviation())));
        }
    }

    private static @Nullable String shiftedStart(@Nullable String original, String grain, int delta) {
        if (original == null) {
            return null;
        }
        return shiftIso(original, grain, delta);
    }

    private static @Nullable String shiftedEnd(@Nullable String original, String grain, int delta) {
        if (original == null) {
            return null;
        }
        return shiftIso(original, grain, delta);
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

    private record BucketPoint(String bucket, double value, long n) {}
}
