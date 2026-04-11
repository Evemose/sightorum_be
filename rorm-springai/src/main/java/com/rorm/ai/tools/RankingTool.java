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
        try {
            if (k <= 0) {
                throw new IllegalArgumentException("k must be > 0");
            }
            var descending = "top".equalsIgnoreCase(direction);
            if (!descending && !"bottom".equalsIgnoreCase(direction)) {
                throw new IllegalArgumentException("direction must be 'top' or 'bottom'");
            }
            var ctx = RormToolContext.from(toolContext);
            var effectiveFilter = withTimeFilter(filter, timeExpression, timeStart, timeEnd);
            var axes = candidateAxes == null ? List.<DenseExpressionDto>of() : candidateAxes;

            var fullRanking = fetchRanking(rootName, rankTargetEntity, measure, kind, denominator,
                effectiveFilter, descending, null, ctx);
            if (fullRanking.isEmpty()) {
                return formatter.error(Archetype.RANKING, "Ranking returned no rows");
            }

            var topK = fullRanking.stream().limit(k).toList();
            var fired = new ArrayList<FiredCheck>();
            var rawMetrics = new LinkedHashMap<String, Object>();
            rawMetrics.put("k", k);
            rawMetrics.put("direction", direction);
            rawMetrics.put("ranked_items", topK.stream().map(RankedItem::toMap).toList());

            evaluateGapSpread(fullRanking, k, fired, rawMetrics);
            evaluateWithinPartitionStability(rootName, rankTargetEntity, measure, kind, denominator,
                effectiveFilter, descending, k, axes, topK, fired, ctx, rawMetrics);
            evaluateSmallN(topK, fired);
            if (survivorshipFlag) {
                fired.add(FiredCheck.low("R5_SURVIVORSHIP",
                    "Population was flagged as filtered to current-only entities — ranking excludes churned/dead items.",
                    Map.of("survivorship_flag", true)));
            }

            var headline = String.format("%s %d by %s: %s",
                descending ? "Top" : "Bottom", k, kind,
                topK.stream().map(r -> r.key() + "=" + String.format("%.4g", r.value()))
                    .reduce((a, b) -> a + ", " + b).orElse(""));
            var receipts = new Receipts(
                timeStart == null && timeEnd == null ? null
                    : (timeStart == null ? "..." : timeStart) + ".." + (timeEnd == null ? "..." : timeEnd),
                effectiveFilter == null ? rootName : rootName + " (filtered)",
                axesNames(axes), Archetype.RANKING, List.of());
            return formatter.format(Archetype.RANKING, headline, fired, receipts, rawMetrics);
        } catch (IllegalArgumentException e) {
            log.warn("Ranking invalid input: {}", e.getMessage());
            return formatter.error(Archetype.RANKING, e.getMessage());
        } catch (Exception e) {
            log.error("Ranking failed", e);
            return formatter.error(Archetype.RANKING, "Ranking failed: " + e.getMessage());
        }
    }

    private List<RankedItem> fetchRanking(String rootName, DenseExpressionDto rankEntity,
                                          DenseExpressionDto measure, String kind,
                                          @Nullable DenseExpressionDto denominator,
                                          @Nullable DenseExpressionDto filter,
                                          boolean descending, @Nullable Long limit,
                                          RormToolContext ctx) {
        var measureSelector = measureSelector(measure, kind, denominator, "value");
        var query = rankedQuery(rootName, rankEntity, filter, measureSelector, descending, limit);
        var rows = executor.execute(query, ctx);
        var items = new ArrayList<RankedItem>();
        for (var row : rows) {
            var n = row.containsKey("n") ? longVal(row, "n") : 0L;
            items.add(new RankedItem(
                String.valueOf(row.get("rank_key")),
                numVal(row, "value"),
                n
            ));
        }
        return items;
    }

    private static void evaluateGapSpread(List<RankedItem> fullRanking, int k,
                                          List<FiredCheck> fired, Map<String, Object> rawMetrics) {
        if (fullRanking.size() <= k || k <= 0) {
            return;
        }
        var values = new double[fullRanking.size()];
        for (var i = 0; i < fullRanking.size(); i++) {
            values[i] = fullRanking.get(i).value();
        }
        var gap = DescriptiveMath.gapSpread(values, k);
        rawMetrics.put("gap_spread", Map.of(
            "boundary_gap", gap.boundaryGap(),
            "median_gap", gap.medianGap(),
            "ratio", gap.ratio()));
        if (gap.gapIsWeak()) {
            fired.add(FiredCheck.med("R2_GAP_TO_SPREAD",
                String.format("Gap at k/k+1 boundary (%.4g) is less than 30%% of median adjacent gap (%.4g) — "
                              + "top-k boundary is fragile; items just below the cutoff may belong in the group.",
                    gap.boundaryGap(), gap.medianGap()),
                Map.of("boundary_gap", gap.boundaryGap(), "median_gap", gap.medianGap(), "ratio", gap.ratio())));
        }
    }

    private void evaluateWithinPartitionStability(String rootName, DenseExpressionDto rankEntity,
                                                  DenseExpressionDto measure, String kind,
                                                  @Nullable DenseExpressionDto denominator,
                                                  @Nullable DenseExpressionDto filter,
                                                  boolean descending, int k,
                                                  List<DenseExpressionDto> axes,
                                                  List<RankedItem> globalTopK,
                                                  List<FiredCheck> fired, RormToolContext ctx,
                                                  Map<String, Object> rawMetrics) {
        if (axes.isEmpty()) {
            return;
        }
        var globalKeys = new HashSet<String>();
        for (var item : globalTopK) {
            globalKeys.add(item.key());
        }

        var churnPerAxis = new ArrayList<Map<String, Object>>();
        var fired1 = false;
        double worstChurn = 0.0;
        var worstAxisIndex = -1;
        for (var i = 0; i < axes.size(); i++) {
            var axis = axes.get(i);
            var segments = fetchDistinctSegments(rootName, axis, filter, ctx);
            if (segments.isEmpty()) {
                continue;
            }
            var perSegmentUnion = new HashSet<String>();
            for (var seg : segments) {
                var segFilter = DenseExpressionDto.binary(axis, "EQUALS", DenseExpressionDto.literal(seg));
                var combined = filter == null
                    ? segFilter
                    : DenseExpressionDto.binary(filter, "AND", segFilter);
                var segRanking = fetchRanking(rootName, rankEntity, measure, kind, denominator,
                    combined, descending, (long) k, ctx);
                for (var r : segRanking) {
                    perSegmentUnion.add(r.key());
                }
            }
            var segOnly = new HashSet<>(perSegmentUnion);
            segOnly.removeAll(globalKeys);
            var churn = perSegmentUnion.isEmpty() ? 0.0
                : segOnly.size() / (double) perSegmentUnion.size();
            churnPerAxis.add(Map.of("axis_index", i, "churn", churn,
                "per_segment_union_size", perSegmentUnion.size(),
                "segment_only_leaders", segOnly.size()));
            if (churn > worstChurn) {
                worstChurn = churn;
                worstAxisIndex = i;
            }
            if (churn > CHURN_THRESHOLD) {
                fired1 = true;
            }
        }
        rawMetrics.put("within_partition_stability", churnPerAxis);
        if (fired1) {
            fired.add(FiredCheck.high("R1_WITHIN_PARTITION_CHURN",
                String.format("Top-%d membership churns %.0f%% across partition axis %d — the global ranking "
                              + "hides partition-level leaders.",
                    k, worstChurn * 100, worstAxisIndex),
                Map.of("worst_axis_index", worstAxisIndex, "worst_churn", worstChurn,
                    "churn_per_axis", churnPerAxis)));
        }
    }

    private static void evaluateSmallN(List<RankedItem> items, List<FiredCheck> fired) {
        var smallItems = new ArrayList<Map<String, Object>>();
        for (var item : items) {
            if (item.n() > 0 && item.n() < SMALL_N_ITEM) {
                smallItems.add(Map.of("key", item.key(), "n", item.n()));
            }
        }
        if (!smallItems.isEmpty()) {
            fired.add(FiredCheck.low("R4_SMALL_N_ITEM",
                smallItems.size() + " ranked item(s) below the 10-row floor — rankings may be noise.",
                Map.of("small_items", smallItems)));
        }
    }

    private static List<String> axesNames(List<DenseExpressionDto> axes) {
        var names = new ArrayList<String>();
        for (var i = 0; i < axes.size(); i++) {
            names.add("axis_" + i);
        }
        return names;
    }

    private List<String> fetchDistinctSegments(String rootName, DenseExpressionDto axis,
                                               @Nullable DenseExpressionDto filter,
                                               RormToolContext ctx) {
        var query = DescriptiveQueryBuilder.groupedQuery(rootName, axis, filter,
            DescriptiveQueryBuilder.countStar("n"));
        var rows = executor.execute(query, ctx);
        var segments = new ArrayList<String>();
        for (var row : rows) {
            segments.add(String.valueOf(row.get("category")));
        }
        return segments;
    }

    private record RankedItem(String key, double value, long n) {
        Map<String, Object> toMap() {
            return Map.of("key", key, "value", value, "n", n);
        }
    }
}
