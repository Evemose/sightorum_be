package com.rorm.ai.tools;

import com.fasterxml.jackson.annotation.JsonClassDescription;
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

import java.util.*;

import static com.rorm.ai.tools.DescriptiveQueryBuilder.*;
import static com.rorm.ai.tools.VerificationQueryExecutor.longVal;
import static com.rorm.ai.tools.VerificationQueryExecutor.numVal;

@SuppressWarnings("unused")
@Slf4j
@Component
@JournaledTool
@RequiredArgsConstructor
public class ComparisonTool {

    private static final String EXPR_HINT =
        "Expression (path for column, or derived). Paths resolve against the FROM root.";

    private static final int SMALL_N_POP = 30;
    private static final double DRIFT_THRESHOLD = 0.20;
    private static final double MAGNITUDE_ORDER_THRESHOLD = 1.0;

    private final VerificationQueryExecutor executor;
    private final AxisFanout axisFanout;
    private final DescriptiveResponseFormatter formatter;

    @Tool(name = "compareSides", description = """
        COMPARISON — Compare a measure between two sides (diff/ratio/direction) with frame-safety checks:
        
        K1 Frame mismatch (HARD BLOCK) — if roots/windows/grains differ without justification, the output blocks.
        K2 Population drift — composition shifts by axis > 20% total variation distance?
        K3 Simpson reversal — do per-segment directions consistently reverse the aggregate?
        K4 Magnitude sanity — are the sides orders of magnitude apart?
        K5 Small-N on either side
        K6 Survivorship flagged on either side
        
        Returns a JSON digest with fired checks (K3 and K1 pinned to top).""")
    public String compareSides(
        @ToolParam(description = "Side A and B specs + relation") ComparisonSides sides,
        @ToolParam(description = "Candidate axes for population drift and Simpson detection.")
        List<DenseExpressionDto> candidateAxes,
        ToolContext toolContext
    ) {
        try {
            validateSides(sides);
            var ctx = RormToolContext.from(toolContext);
            var axes = candidateAxes == null ? List.<DenseExpressionDto>of() : candidateAxes;
            var fired = new ArrayList<FiredCheck>();
            var rawMetrics = new LinkedHashMap<String, Object>();

            var mismatch = evaluateFrameMismatch(sides);
            if (mismatch != null) {
                fired.add(mismatch);
                var receipts = buildReceipts(sides, axes);
                return formatter.format(Archetype.COMPARISON,
                    "Comparison blocked by frame mismatch",
                    fired, receipts, rawMetrics);
            }

            var sideA = fetchSideValue(sides.sideA(), ctx);
            var sideB = fetchSideValue(sides.sideB(), ctx);
            rawMetrics.put("side_a_value", sideA.value());
            rawMetrics.put("side_a_n", sideA.n());
            rawMetrics.put("side_b_value", sideB.value());
            rawMetrics.put("side_b_n", sideB.n());

            var relationResult = computeRelation(sideA.value(), sideB.value(), sides.relation());
            rawMetrics.put("relation_result", relationResult);

            evaluateSimpsonReversal(sides, sideA.value(), sideB.value(), axes, fired, ctx, rawMetrics);
            evaluatePopulationDrift(sides, axes, fired, ctx, rawMetrics);
            evaluateMagnitudeSanity(sideA.value(), sideB.value(), fired);
            evaluateSmallN(sideA.n(), sideB.n(), fired);
            if (sides.sideA().survivorshipFlag() || sides.sideB().survivorshipFlag()) {
                fired.add(FiredCheck.low("K6_SURVIVORSHIP",
                    "One or both sides flagged as filtered to current-only entities.",
                    Map.of("side_a", sides.sideA().survivorshipFlag(), "side_b", sides.sideB().survivorshipFlag())));
            }

            var headline = String.format("%s %s %s = %s (A=%.4g N=%d, B=%.4g N=%d)",
                sides.sideA().label() == null ? "A" : sides.sideA().label(),
                relationWord(sides.relation()),
                sides.sideB().label() == null ? "B" : sides.sideB().label(),
                formatRelationResult(sides.relation(), relationResult),
                sideA.value(), sideA.n(), sideB.value(), sideB.n());
            var receipts = buildReceipts(sides, axes);
            return formatter.format(Archetype.COMPARISON, headline, fired, receipts, rawMetrics);
        } catch (IllegalArgumentException e) {
            log.warn("Comparison invalid input: {}", e.getMessage());
            return formatter.error(Archetype.COMPARISON, e.getMessage());
        } catch (Exception e) {
            log.error("Comparison failed", e);
            return formatter.error(Archetype.COMPARISON, "Comparison failed: " + e.getMessage());
        }
    }

    private static void validateSides(ComparisonSides sides) {
        if (sides == null || sides.sideA() == null || sides.sideB() == null) {
            throw new IllegalArgumentException("Both sides required");
        }
        if (sides.relation() == null) {
            throw new IllegalArgumentException("relation required (diff|ratio|direction)");
        }
    }

    private @Nullable FiredCheck evaluateFrameMismatch(ComparisonSides sides) {
        var a = sides.sideA();
        var b = sides.sideB();
        var mismatches = new ArrayList<String>();
        if (!Objects.equals(a.rootName(), b.rootName())) {
            mismatches.add("root (" + a.rootName() + " vs " + b.rootName() + ")");
        }
        if (!Objects.equals(a.kind(), b.kind())) {
            mismatches.add("kind (" + a.kind() + " vs " + b.kind() + ")");
        }
        if (!Objects.equals(a.bucketGrain(), b.bucketGrain())) {
            mismatches.add("grain (" + a.bucketGrain() + " vs " + b.bucketGrain() + ")");
        }
        if (mismatches.isEmpty()) {
            return null;
        }
        if (sides.justifyMismatch()) {
            return null;
        }
        return FiredCheck.high("K1_FRAME_MISMATCH",
            "Sides differ in: " + String.join(", ", mismatches)
            + ". Comparison blocked. Set justifyMismatch=true to proceed explicitly.",
            Map.of("mismatches", mismatches));
    }

    private static Receipts buildReceipts(ComparisonSides sides, List<DenseExpressionDto> axes) {
        var window = (sides.sideA().timeStart() == null && sides.sideA().timeEnd() == null) ? null
            : "A:" + timeWindowString(sides.sideA()) + " / B:" + timeWindowString(sides.sideB());
        var population = sides.sideA().rootName() + " vs " + sides.sideB().rootName();
        var axesNames = new ArrayList<String>();
        for (var i = 0; i < axes.size(); i++) {
            axesNames.add("axis_" + i);
        }
        return new Receipts(window, population, axesNames, Archetype.COMPARISON, List.of());
    }

    private SideResult fetchSideValue(SideSpec side, RormToolContext ctx) {
        var effectiveFilter = withTimeFilter(side.filter(), side.timeExpression(),
            side.timeStart(), side.timeEnd());
        var row = executor.executeSingle(plainQuery(side.rootName(), effectiveFilter,
            measureSelector(side.measure(), side.kind(), side.denominator(), "value"),
            countStar("n")
        ), ctx);
        return new SideResult(numVal(row, "value"), longVal(row, "n"));
    }

    private static double computeRelation(double a, double b, String relation) {
        return switch (relation.toLowerCase()) {
            case "diff" -> a - b;
            case "ratio" -> b == 0.0 ? Double.NaN : a / b;
            case "direction" -> Double.compare(a, b);
            default -> throw new IllegalArgumentException("Unknown relation: " + relation);
        };
    }

    private void evaluateSimpsonReversal(ComparisonSides sides, double aggA, double aggB,
                                         List<DenseExpressionDto> axes, List<FiredCheck> fired,
                                         RormToolContext ctx, Map<String, Object> rawMetrics) {
        if (axes.isEmpty()) {
            return;
        }
        var aggregateDir = DescriptiveMath.direction(aggA - aggB, 1e-9);
        if (aggregateDir == DescriptiveMath.Direction.FLAT) {
            return;
        }
        var filterA = withTimeFilter(sides.sideA().filter(), sides.sideA().timeExpression(),
            sides.sideA().timeStart(), sides.sideA().timeEnd());
        var filterB = withTimeFilter(sides.sideB().filter(), sides.sideB().timeExpression(),
            sides.sideB().timeStart(), sides.sideB().timeEnd());
        var resultsA = axisFanout.fanout(sides.sideA().rootName(), filterA,
            sides.sideA().measure(), sides.sideA().kind(), sides.sideA().denominator(), axes, ctx);
        var resultsB = axisFanout.fanout(sides.sideB().rootName(), filterB,
            sides.sideB().measure(), sides.sideB().kind(), sides.sideB().denominator(), axes, ctx);
        for (var i = 0; i < axes.size(); i++) {
            var segmentsA = toMap(resultsA.get(i).segments());
            var segmentsB = toMap(resultsB.get(i).segments());
            var sharedKeys = new ArrayList<String>();
            for (var k : segmentsA.keySet()) {
                if (segmentsB.containsKey(k)) {
                    sharedKeys.add(k);
                }
            }
            if (sharedKeys.size() < 2) {
                continue;
            }
            var directions = sharedKeys.stream()
                .map(k -> DescriptiveMath.direction(segmentsA.get(k) - segmentsB.get(k), 1e-9))
                .toList();
            var majority = DescriptiveMath.directionMajority(directions, aggregateDir);
            if (majority.disagree() > majority.agree() && majority.disagree() >= sharedKeys.size() / 2) {
                fired.add(FiredCheck.high("K3_SIMPSON_REVERSAL",
                    String.format("Aggregate says A %s B but %d/%d segments on axis %d show the opposite — Simpson's paradox.",
                        aggregateDir == DescriptiveMath.Direction.UP ? ">" : "<",
                        majority.disagree(), sharedKeys.size(), i),
                    Map.of("axis_index", i, "agree", majority.agree(), "disagree", majority.disagree(),
                        "shared_segments", sharedKeys)));
                return;
            }
        }
    }

    private void evaluatePopulationDrift(ComparisonSides sides, List<DenseExpressionDto> axes,
                                         List<FiredCheck> fired, RormToolContext ctx,
                                         Map<String, Object> rawMetrics) {
        if (axes.isEmpty()) {
            return;
        }
        var filterA = withTimeFilter(sides.sideA().filter(), sides.sideA().timeExpression(),
            sides.sideA().timeStart(), sides.sideA().timeEnd());
        var filterB = withTimeFilter(sides.sideB().filter(), sides.sideB().timeExpression(),
            sides.sideB().timeStart(), sides.sideB().timeEnd());
        var driftPerAxis = new ArrayList<Map<String, Object>>();
        var worstAxis = -1;
        var worstDistance = 0.0;
        for (var i = 0; i < axes.size(); i++) {
            var axis = axes.get(i);
            var countsA = fetchSegmentCounts(sides.sideA().rootName(), axis, filterA, ctx);
            var countsB = fetchSegmentCounts(sides.sideB().rootName(), axis, filterB, ctx);
            var distance = DescriptiveMath.totalVariationDistance(countsA, countsB);
            driftPerAxis.add(Map.of("axis_index", i, "tv_distance", distance));
            if (distance > worstDistance) {
                worstDistance = distance;
                worstAxis = i;
            }
        }
        rawMetrics.put("population_drift", driftPerAxis);
        if (worstDistance > DRIFT_THRESHOLD) {
            fired.add(FiredCheck.high("K2_POPULATION_DRIFT",
                String.format("Segment composition differs by TV distance %.2f on axis %d — populations are not comparable.",
                    worstDistance, worstAxis),
                Map.of("worst_axis_index", worstAxis, "worst_tv_distance", worstDistance)));
        }
    }

    private static void evaluateMagnitudeSanity(double a, double b, List<FiredCheck> fired) {
        if (a == 0.0 || b == 0.0) {
            return;
        }
        var logRatio = Math.abs(Math.log10(Math.abs(a) / Math.abs(b)));
        if (logRatio > MAGNITUDE_ORDER_THRESHOLD) {
            fired.add(FiredCheck.med("K4_MAGNITUDE_MISMATCH",
                String.format("Sides differ by %.1f orders of magnitude — comparison may be apples-to-oranges.", logRatio),
                Map.of("log10_ratio", logRatio)));
        }
    }

    private static void evaluateSmallN(long nA, long nB, List<FiredCheck> fired) {
        var reasons = new ArrayList<String>();
        if (nA < SMALL_N_POP) {
            reasons.add("side A N=" + nA + " < " + SMALL_N_POP);
        }
        if (nB < SMALL_N_POP) {
            reasons.add("side B N=" + nB + " < " + SMALL_N_POP);
        }
        if (!reasons.isEmpty()) {
            fired.add(FiredCheck.low("K5_SMALL_N",
                "Sample size concern: " + String.join("; ", reasons),
                Map.of("reasons", reasons, "n_a", nA, "n_b", nB)));
        }
    }

    private static String relationWord(String relation) {
        return switch (relation.toLowerCase()) {
            case "diff" -> "minus";
            case "ratio" -> "over";
            case "direction" -> "vs";
            default -> relation;
        };
    }

    private static String formatRelationResult(String relation, double result) {
        return switch (relation.toLowerCase()) {
            case "direction" -> result > 0 ? "A > B" : result < 0 ? "A < B" : "A = B";
            default -> String.format("%.4g", result);
        };
    }

    private static String timeWindowString(SideSpec side) {
        return (side.timeStart() == null ? "..." : side.timeStart())
               + ".." + (side.timeEnd() == null ? "..." : side.timeEnd());
    }

    private static Map<String, Double> toMap(List<AxisFanout.Segment> segments) {
        var map = new HashMap<String, Double>();
        for (var s : segments) {
            map.put(s.key(), s.value());
        }
        return map;
    }

    private Map<String, Long> fetchSegmentCounts(String rootName, DenseExpressionDto axis,
                                                 @Nullable DenseExpressionDto filter,
                                                 RormToolContext ctx) {
        var query = DescriptiveQueryBuilder.groupedQuery(rootName, axis, filter,
            DescriptiveQueryBuilder.countStar("n"));
        var rows = executor.execute(query, ctx);
        var result = new HashMap<String, Long>();
        for (var row : rows) {
            result.put(String.valueOf(row.get("category")), longVal(row, "n"));
        }
        return result;
    }

    private record SideResult(double value, long n) {}

    @JsonClassDescription("Two-sided comparison spec: side A, side B, the relation to compute, and whether to bypass frame-mismatch block.")
    public record ComparisonSides(
        SideSpec sideA,
        SideSpec sideB,
        String relation,
        boolean justifyMismatch
    ) {}

    @JsonClassDescription("A single side of a comparison: root, measure, kind, optional filter/time window, survivorship flag.")
    public record SideSpec(
        String rootName,
        @Nullable String label,
        DenseExpressionDto measure,
        String kind,
        @Nullable DenseExpressionDto denominator,
        @Nullable DenseExpressionDto filter,
        @Nullable DenseExpressionDto timeExpression,
        @Nullable String timeStart,
        @Nullable String timeEnd,
        @Nullable String bucketGrain,
        boolean survivorshipFlag
    ) {}
}
