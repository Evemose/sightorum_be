package com.rorm.ai.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rorm.ai.JournaledTool;
import com.rorm.ai.RormToolContext;
import com.rorm.dto.dense.DenseExpressionDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.rorm.ai.tools.StatisticalPrimitives.*;
import static com.rorm.ai.tools.VerificationQueryBuilder.*;
import static com.rorm.ai.tools.VerificationQueryExecutor.numVal;

@SuppressWarnings("unused")
@Slf4j
@Component
@JournaledTool
@RequiredArgsConstructor
public class DataExplorationTool {

    private static final String EXPR_HINT = "Expression (path for column, or derived). Paths resolve against the FROM root.";

    private final VerificationQueryExecutor executor;
    private final ObjectMapper objectMapper;

    @Tool(description = """
        STRATIFIED GRADIENT — How does feature X relate to outcome Y within each category of Z?
        
        Groups by Z, computes per-group: CORR(X, Y), REGR_SLOPE(Y, X), AVG(Y), AVG(X), COUNT(*).
        This is the most common query pattern: generator uses it for exploration, skeptic for proxy absorption
        and ecological fallacy checks, executor for heterogeneity analysis.
        
        Returns per-group gradient data, not a verdict.""")
    public String stratifiedGradient(
        @ToolParam(description = "Root entity name") String rootName,
        @ToolParam(description = "Feature variable whose gradient to measure. " + EXPR_HINT) DenseExpressionDto feature,
        @ToolParam(description = "Outcome variable. " + EXPR_HINT) DenseExpressionDto outcome,
        @ToolParam(description = "Categorical variable to stratify by. " + EXPR_HINT) DenseExpressionDto groupBy,
        @ToolParam(description = "Optional WHERE filter") @Nullable DenseExpressionDto filter,
        ToolContext toolContext
    ) {
        try {
            var ctx = RormToolContext.from(toolContext);
            executor.requireCorrCompatible(feature, "feature", rootName, ctx);
            executor.requireCorrCompatible(outcome, "outcome", rootName, ctx);

            var results = executor.execute(groupedQuery(rootName, groupBy, filter,
                corr(feature, outcome, "gradient"),
                regrSlope(outcome, feature, "slope"),
                avg(outcome, "avg_outcome"),
                avg(feature, "avg_feature"),
                count("n")
            ), ctx);

            var groups = parseGroupGradients(results);

            return toJson(Map.of("success", true,
                "groups", groups.stream().map(GroupGradient::toMap).toList(),
                "total_groups", groups.size()));
        } catch (Exception e) {
            log.error("Stratified gradient failed", e);
            return errorJson("Stratified gradient failed: " + e.getMessage());
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return errorJson("Serialization failed: " + e.getMessage());
        }
    }

    private String errorJson(String message) {
        try {
            return objectMapper.writeValueAsString(Map.of("success", false, "error", message));
        } catch (JsonProcessingException e) {
            return "{\"success\":false,\"error\":\"" + message.replace("\"", "\\\"") + "\"}";
        }
    }

    @Tool(description = """
        CROSS TABULATION — Joint distribution of two variables with outcome rates per cell.
        
        Groups by (variableA, variableB), computes per cell: n, outcome_rate, pct_of_total.
        Also detects subset relationships: is A ⊂ B? is B ⊂ A? Are they independent?
        
        Useful for rare event overlap checks, independence verification, and interaction detection.""")
    public String crossTabulation(
        @ToolParam(description = "Root entity name") String rootName,
        @ToolParam(description = "First categorical/bucketed variable. " + EXPR_HINT) DenseExpressionDto variableA,
        @ToolParam(description = "Second categorical/bucketed variable. " + EXPR_HINT) DenseExpressionDto variableB,
        @ToolParam(description = "Outcome variable. " + EXPR_HINT) DenseExpressionDto outcome,
        @ToolParam(description = "Optional WHERE filter") @Nullable DenseExpressionDto filter,
        ToolContext toolContext
    ) {
        try {
            var ctx = RormToolContext.from(toolContext);
            executor.requireCorrCompatible(outcome, "outcome", rootName, ctx);

            var results = executor.execute(doubleGroupedQuery(rootName, variableA, variableB, filter,
                avg(outcome, "outcome_rate"), count("n")
            ), ctx);

            var crossTab = buildCrossTab(results);

            return toJson(Map.of(
                "success", true,
                "cells", crossTab.cells().stream().map(Cell::toMap).toList(),
                "total_n", crossTab.totalN(),
                "margin_a", crossTab.marginA(),
                "margin_b", crossTab.marginB(),
                "overlap", crossTab.overlap()
            ));
        } catch (Exception e) {
            log.error("Cross tabulation failed", e);
            return errorJson("Cross tabulation failed: " + e.getMessage());
        }
    }

    @Tool(description = """
        THRESHOLD LOCATION — Bin a continuous variable and find the outcome inflection point.
        
        Divides the feature into equal-width bins, computes outcome rate per bin, and detects
        the bin boundary where the largest absolute change in outcome rate occurs.
        Compares detected inflection to a claimed threshold.
        
        Useful for verifying SHAP breakpoints, finding non-monotonicity, and refining thresholds.""")
    public String thresholdLocation(
        @ToolParam(description = "Root entity name") String rootName,
        @ToolParam(description = "Continuous feature to bin. " + EXPR_HINT) DenseExpressionDto feature,
        @ToolParam(description = "Outcome variable. " + EXPR_HINT) DenseExpressionDto outcome,
        @ToolParam(description = "Generator's claimed breakpoint value") double claimedThreshold,
        @ToolParam(description = "Number of equal-width bins (default 10). For skewed features, pass a transformed expression (e.g. LOG) to get meaningful bins.") int nBins,
        @ToolParam(description = "Optional WHERE filter") @Nullable DenseExpressionDto filter,
        ToolContext toolContext
    ) {
        try {
            var ctx = RormToolContext.from(toolContext);
            executor.requireCorrCompatible(feature, "feature", rootName, ctx);
            executor.requireCorrCompatible(outcome, "outcome", rootName, ctx);

            var rangeRow = executor.executeSingle(VerificationQueryBuilder.query(rootName, filter,
                new com.rorm.dto.dense.DenseQueryDto.SelectedExpressionDto(
                    DenseExpressionDto.aggregation("MIN", List.of(feature), false), "feat_min"),
                new com.rorm.dto.dense.DenseQueryDto.SelectedExpressionDto(
                    DenseExpressionDto.aggregation("MAX", List.of(feature), false), "feat_max")
            ), ctx);

            var featMin = numVal(rangeRow, "feat_min");
            var featMax = numVal(rangeRow, "feat_max");
            if (featMax - featMin < 1e-10) {
                return errorJson("Feature has zero range — cannot bin.");
            }

            var bins = computeBinsServerSide(rootName, feature, outcome, filter, ctx,
                featMin, featMax, Math.max(2, nBins));
            if (bins.isEmpty()) {
                return errorJson("No data for binning.");
            }

            var inflection = detectInflection(bins);

            return toJson(Map.of(
                "success", true,
                "bins", bins,
                "feature_range", Map.of("min", featMin, "max", featMax),
                "detected_inflection", inflection.point(),
                "max_delta", inflection.maxDelta(),
                "claimed_threshold", claimedThreshold,
                "claimed_vs_detected_delta", Math.abs(claimedThreshold - inflection.point())
            ));
        } catch (Exception e) {
            log.error("Threshold location failed", e);
            return errorJson("Threshold location failed: " + e.getMessage());
        }
    }

    private List<Map<String, Object>> computeBinsServerSide(
        String rootName, DenseExpressionDto feature, DenseExpressionDto outcome,
        @Nullable DenseExpressionDto filter, RormToolContext ctx,
        double featMin, double featMax, int nBins
    ) {
        var binWidth = (featMax - featMin) / nBins;
        var binExpr = DenseExpressionDto.functionCall("LEAST", List.of(
            DenseExpressionDto.functionCall("FLOOR", List.of(
                DenseExpressionDto.binary(
                    DenseExpressionDto.binary(feature, "SUBTRACT", DenseExpressionDto.literal(featMin)),
                    "DIVIDE",
                    DenseExpressionDto.literal(binWidth)))),
            DenseExpressionDto.literal(nBins - 1)));

        var results = executor.execute(groupedQuery(rootName, binExpr, filter,
            avg(outcome, "outcome_rate"), avg(feature, "avg_feature"), count("n")
        ), ctx);

        results.sort(Comparator.comparingDouble(r -> numVal(r, "avg_feature")));

        return results.stream()
            .map(row -> Map.<String, Object>of(
                "bin_midpoint", numVal(row, "avg_feature"),
                "outcome_rate", numVal(row, "outcome_rate"),
                "n", VerificationQueryExecutor.longVal(row, "n")))
            .toList();
    }

    @Tool(description = """
        DEPLOYMENT DISTRIBUTION — Treatment level composition across groups with concentration metrics.
        
        Groups by (treatment, groupBy), computes per treatment level: total n, per-group n and
        outcome rate, pct of level in each group, and a Herfindahl-Hirschman Index (HHI) measuring
        how concentrated the deployment is.
        
        HHI close to 1.0 means highly concentrated (most units in one group).
        HHI close to 1/k (k groups) means uniform.
        
        Useful for detecting allocation bias before making causal claims.""")
    public String deploymentDistribution(
        @ToolParam(description = "Root entity name") String rootName,
        @ToolParam(description = "Treatment variable (the one whose deployment to check). " + EXPR_HINT) DenseExpressionDto treatment,
        @ToolParam(description = "Grouping variable (e.g. hubId, region). " + EXPR_HINT) DenseExpressionDto groupBy,
        @ToolParam(description = "Outcome variable. " + EXPR_HINT) DenseExpressionDto outcome,
        @ToolParam(description = "Optional WHERE filter") @Nullable DenseExpressionDto filter,
        ToolContext toolContext
    ) {
        try {
            var ctx = RormToolContext.from(toolContext);
            executor.requireCorrCompatible(outcome, "outcome", rootName, ctx);

            var results = executor.execute(doubleGroupedQuery(rootName, treatment, groupBy, filter,
                avg(outcome, "outcome_rate"), count("n")
            ), ctx);

            var marginalResults = executor.execute(groupedQuery(rootName, groupBy, filter,
                avg(outcome, "outcome_rate"), count("n")
            ), ctx);
            var marginalRates = new HashMap<String, Double>();
            for (var row : marginalResults) {
                marginalRates.put(String.valueOf(row.get("category")), numVal(row, "outcome_rate"));
            }

            var levels = computeLevelDistributions(results);

            return toJson(Map.of("success", true,
                "levels", levels.stream().map(LevelDistribution::toMap).toList(),
                "marginal_outcome_per_group", marginalRates));
        } catch (Exception e) {
            log.error("Deployment distribution failed", e);
            return errorJson("Deployment distribution failed: " + e.getMessage());
        }
    }
}
