package com.rorm.ai.tools;

import com.rorm.ai.JournaledTool;
import com.rorm.ai.RormToolContext;
import com.rorm.ai.tools.DescriptiveDigest.Archetype;
import com.rorm.ai.tools.DescriptiveDigest.FiredCheck;
import com.rorm.ai.tools.DescriptiveDigest.Receipts;
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

import static com.rorm.ai.tools.DescriptiveQueryBuilder.*;
import static com.rorm.ai.tools.VerificationQueryExecutor.longVal;
import static com.rorm.ai.tools.VerificationQueryExecutor.numVal;

@SuppressWarnings("unused")
@Slf4j
@Component
@JournaledTool
@RequiredArgsConstructor
public class RankingTool {

    private static final String EXPR_HINT =
        "Expression (path for column, or derived). Paths resolve against the FROM root.";
    private static final double CHURN_THRESHOLD = 0.30;
    private static final int SMALL_N_ITEM = 10;

    private static final ScopedValue<RankingContext> RANKING_CTX = ScopedValue.newInstance();

    private final VerificationQueryExecutor executor;
    private final DescriptiveResponseFormatter formatter;

    @Tool(name = "rankedList", description = """
        RANKING — Top/bottom K entities by measure, with deterministic robustness checks:

        R1 Within-partition stability — does top-k membership churn >30% when ranking inside partitions?
        R2 Gap-to-spread — is the k/k+1 boundary gap less than 30% of the median adjacent gap?
        R4 Small-N contamination — are any ranked items below the 10-row floor?
        R5 Survivorship disclosure — fired when survivorshipFlag is true

        R3 (measure sensitivity) is not implemented in v1 — no near-equivalent-measure resolver.

        Returns a JSON digest with fired checks and receipts.""")
    public String rankedList(
        @ToolParam(description = "Root entity name") String rootName,
        @ToolParam(description = "Entity expression to rank (e.g. customer_id or region). " + EXPR_HINT)
        DenseExpressionDto rankTargetEntity,
        @ToolParam(description = "Measure expression to rank by. " + EXPR_HINT) DenseExpressionDto measure,
        @ToolParam(description = "Measure kind: total|mean|count|ratio") String kind,
        @ToolParam(description = "Denominator for kind=ratio. " + EXPR_HINT) @Nullable DenseExpressionDto denominator,
        @ToolParam(description = "Number of items to surface") int k,
        @ToolParam(description = "Direction: top (descending) or bottom (ascending)") String direction,
        @ToolParam(description = "Optional WHERE filter. " + EXPR_HINT) @Nullable DenseExpressionDto filter,
        @ToolParam(description = "Optional time expression. " + EXPR_HINT) @Nullable DenseExpressionDto timeExpression,
        @ToolParam(description = "Time window start (ISO). Requires timeExpression.") @Nullable String timeStart,
        @ToolParam(description = "Time window end (ISO, exclusive). Requires timeExpression.") @Nullable String timeEnd,
        @ToolParam(description = "Candidate partition axes for within-partition stability check")
        List<DenseExpressionDto> candidateAxes,
        @ToolParam(description = "True when population is filtered to current-only entities. Fires R5.")
        boolean survivorshipFlag,
        ToolContext toolContext
    ) {
        var spec = new RankingSpec(rootName, rankTargetEntity, measure, kind, denominator, k, direction,
            filter, timeExpression, timeStart, timeEnd, candidateAxes, survivorshipFlag);
        return ScopedValue.where(RANKING_CTX, new RankingContext(spec, RormToolContext.from(toolContext)))
            .call(this::executeRanking);
    }

    private String executeRanking() {
        try {
            var spec = RANKING_CTX.get().spec;
            validateInputs(spec.k, spec.direction);

            var fullRanking = fetchRanking(null);
            if (fullRanking.isEmpty()) {
                return formatter.error(Archetype.RANKING, "Ranking returned no rows");
            }

            var topK = fullRanking.stream().limit(spec.k).toList();
            var collector = new CheckCollector();
            buildBasicMetrics(topK, spec.k, spec.direction, collector.rawMetrics);

            runAllChecks(fullRanking, topK, collector);

            return formatResponse(topK, collector);
        } catch (IllegalArgumentException e) {
            log.warn("Ranking invalid input: {}", e.getMessage());
            return formatter.error(Archetype.RANKING, e.getMessage());
        } catch (Exception e) {
            log.error("Ranking failed", e);
            return formatter.error(Archetype.RANKING, "Ranking failed: " + e.getMessage());
        }
    }

    private void validateInputs(int k, String direction) {
        if (k <= 0) {
            throw new IllegalArgumentException("k must be > 0");
        }
        if (!"top".equalsIgnoreCase(direction) && !"bottom".equalsIgnoreCase(direction)) {
            throw new IllegalArgumentException("direction must be 'top' or 'bottom'");
        }
    }

    private List<RankedItem> fetchRanking(@Nullable DenseExpressionDto overrideFilter) {
        var ctx = RANKING_CTX.get();
        var spec = ctx.spec;
        var filter = overrideFilter != null ? overrideFilter : ctx.effectiveFilter;

        var measureSelector = measureSelector(spec.measure, spec.kind, spec.denominator, "value");
        var query = rankedQuery(spec.rootName, spec.rankTargetEntity, filter, measureSelector,
            ctx.descending, null);
        var rows = executor.execute(query, ctx.toolCtx);
        return rows.stream()
            .map(row -> new RankedItem(
                String.valueOf(row.get("rank_key")),
                numVal(row, "value"),
                row.containsKey("n") ? longVal(row, "n") : 0L))
            .toList();
    }

    private void buildBasicMetrics(List<RankedItem> topK, int k, String direction,
                                   Map<String, Object> rawMetrics) {
        rawMetrics.put("k", k);
        rawMetrics.put("direction", direction);
        rawMetrics.put("ranked_items", topK.stream().map(RankedItem::toMap).toList());
    }

    private void runAllChecks(List<RankedItem> fullRanking, List<RankedItem> topK,
                              CheckCollector collector) {
        evaluateGapSpread(fullRanking, collector);
        evaluateWithinPartitionStability(topK, collector);
        evaluateSmallN(topK, collector.fired);
        evaluateSurvivorship(collector.fired);
    }

    private String formatResponse(List<RankedItem> topK, CheckCollector collector) {
        var ctx = RANKING_CTX.get();
        var spec = ctx.spec;

        var headline = String.format("%s %d by %s: %s",
            ctx.descending ? "Top" : "Bottom", spec.k, spec.kind,
            topK.stream().map(r -> r.key() + "=" + String.format("%.4g", r.value()))
                .reduce((a, b) -> a + ", " + b).orElse(""));
        var receipts = new Receipts(
            spec.timeStart == null && spec.timeEnd == null ? null
                : (spec.timeStart == null ? "..." : spec.timeStart) + ".."
                  + (spec.timeEnd == null ? "..." : spec.timeEnd),
            ctx.effectiveFilter == null ? spec.rootName : spec.rootName + " (filtered)",
            axesNames(ctx.axes), Archetype.RANKING, List.of());
        return formatter.format(Archetype.RANKING, headline, collector.fired, receipts,
            collector.rawMetrics);
    }

    private void evaluateGapSpread(List<RankedItem> fullRanking, CheckCollector collector) {
        var k = RANKING_CTX.get().spec.k;
        if (fullRanking.size() <= k || k <= 0) {
            return;
        }
        var values = fullRanking.stream().mapToDouble(RankedItem::value).toArray();
        var gap = DescriptiveMath.gapSpread(values, k);
        collector.rawMetrics.put("gap_spread", Map.of(
            "boundary_gap", gap.boundaryGap(),
            "median_gap", gap.medianGap(),
            "ratio", gap.ratio()));
        if (gap.gapIsWeak()) {
            collector.fired.add(FiredCheck.med("R2_GAP_TO_SPREAD",
                String.format("Gap at k/k+1 boundary (%.4g) is less than 30%% of median adjacent gap (%.4g) — "
                              + "top-k boundary is fragile; items just below the cutoff may belong in the group.",
                    gap.boundaryGap(), gap.medianGap()),
                Map.of("boundary_gap", gap.boundaryGap(), "median_gap", gap.medianGap(), "ratio", gap.ratio())));
        }
    }

    private void evaluateWithinPartitionStability(List<RankedItem> globalTopK,
                                                  CheckCollector collector) {
        var ctx = RANKING_CTX.get();
        if (ctx.axes.isEmpty()) {
            return;
        }

        var globalKeys = globalTopK.stream().map(RankedItem::key).collect(java.util.stream.Collectors.toSet());
        var churnPerAxis = new ArrayList<Map<String, Object>>();
        var fired1 = false;
        double worstChurn = 0.0;
        var worstAxisIndex = -1;

        for (var i = 0; i < ctx.axes.size(); i++) {
            var churnResult = evaluateAxisChurn(ctx.axes.get(i), i, globalKeys);
            churnPerAxis.add(churnResult.metrics);
            if (churnResult.churn > worstChurn) {
                worstChurn = churnResult.churn;
                worstAxisIndex = i;
            }
            if (churnResult.churn > CHURN_THRESHOLD) {
                fired1 = true;
            }
        }

        collector.rawMetrics.put("within_partition_stability", churnPerAxis);
        if (fired1) {
            collector.fired.add(FiredCheck.high("R1_WITHIN_PARTITION_CHURN",
                String.format("Top-%d membership churns %.0f%% across partition axis %d — the global ranking "
                              + "hides partition-level leaders.",
                    ctx.spec.k, worstChurn * 100, worstAxisIndex),
                Map.of("worst_axis_index", worstAxisIndex, "worst_churn", worstChurn,
                    "churn_per_axis", churnPerAxis)));
        }
    }

    private static void evaluateSmallN(List<RankedItem> items, List<FiredCheck> fired) {
        var smallItems = items.stream()
            .filter(item -> item.n() > 0 && item.n() < SMALL_N_ITEM)
            .map(item -> Map.of("key", item.key(), "n", item.n()))
            .toList();
        if (!smallItems.isEmpty()) {
            fired.add(FiredCheck.low("R4_SMALL_N_ITEM",
                smallItems.size() + " ranked item(s) below the 10-row floor — rankings may be noise.",
                Map.of("small_items", smallItems)));
        }
    }

    private void evaluateSurvivorship(List<FiredCheck> fired) {
        if (RANKING_CTX.get().spec.survivorshipFlag) {
            fired.add(FiredCheck.low("R5_SURVIVORSHIP",
                "Population was flagged as filtered to current-only entities — ranking excludes churned/dead items.",
                Map.of("survivorship_flag", true)));
        }
    }

    private ChurnResult evaluateAxisChurn(DenseExpressionDto axis, int axisIndex,
                                          Set<String> globalKeys) {
        var ctx = RANKING_CTX.get();
        var segments = fetchDistinctSegments(axis);
        if (segments.isEmpty()) {
            return new ChurnResult(0.0, Map.of("axis_index", axisIndex, "churn", 0.0));
        }

        var perSegmentUnion = new HashSet<String>();
        for (var seg : segments) {
            var segFilter = createSegmentFilter(axis, seg, ctx.effectiveFilter);
            var segRanking = fetchRanking(segFilter);
            segRanking.stream().limit(ctx.spec.k).forEach(r -> perSegmentUnion.add(r.key()));
        }

        var segOnly = new HashSet<>(perSegmentUnion);
        segOnly.removeAll(globalKeys);
        var churn = perSegmentUnion.isEmpty() ? 0.0 : segOnly.size() / (double) perSegmentUnion.size();

        return new ChurnResult(churn, Map.of("axis_index", axisIndex, "churn", churn,
            "per_segment_union_size", perSegmentUnion.size(),
            "segment_only_leaders", segOnly.size()));
    }

    private List<String> fetchDistinctSegments(DenseExpressionDto axis) {
        var ctx = RANKING_CTX.get();
        var query = DescriptiveQueryBuilder.groupedQuery(ctx.spec.rootName, axis,
            ctx.effectiveFilter, DescriptiveQueryBuilder.countStar("n"));
        return executor.execute(query, ctx.toolCtx).stream()
            .map(row -> String.valueOf(row.get("category")))
            .toList();
    }

    private DenseExpressionDto createSegmentFilter(DenseExpressionDto axis, String seg,
                                                   @Nullable DenseExpressionDto baseFilter) {
        var segFilter = DenseExpressionDto.binary(axis, "EQUALS", DenseExpressionDto.literal(seg));
        return baseFilter == null ? segFilter : DenseExpressionDto.binary(baseFilter, "AND", segFilter);
    }

    record RankingSpec(
        String rootName, DenseExpressionDto rankTargetEntity, DenseExpressionDto measure,
        String kind, @Nullable DenseExpressionDto denominator, int k, String direction,
        @Nullable DenseExpressionDto filter, @Nullable DenseExpressionDto timeExpression,
        @Nullable String timeStart, @Nullable String timeEnd,
        List<DenseExpressionDto> candidateAxes, boolean survivorshipFlag
    ) {}

    record RankingContext(
        RankingSpec spec, RormToolContext toolCtx,
        DenseExpressionDto effectiveFilter, List<DenseExpressionDto> axes, boolean descending
    ) {
        RankingContext(RankingSpec spec, RormToolContext toolCtx) {
            this(spec, toolCtx,
                withTimeFilter(spec.filter, spec.timeExpression, spec.timeStart, spec.timeEnd),
                spec.candidateAxes == null ? List.of() : spec.candidateAxes,
                "top".equalsIgnoreCase(spec.direction));
        }
    }

    private static List<String> axesNames(List<DenseExpressionDto> axes) {
        var names = new ArrayList<String>();
        for (var i = 0; i < axes.size(); i++) {
            names.add("axis_" + i);
        }
        return names;
    }

    private record ChurnResult(double churn, Map<String, Object> metrics) {}

    private record RankedItem(String key, double value, long n) {
        Map<String, Object> toMap() {
            return Map.of("key", key, "value", value, "n", n);
        }
    }
}
