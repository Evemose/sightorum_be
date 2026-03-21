package com.rorm.ai.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rorm.ai.JournaledTool;
import com.rorm.ai.RormToolContext;
import com.rorm.dto.dense.DenseExpressionDto;
import com.rorm.dto.dense.DenseQueryDto;
import com.rorm.dto.dense.DenseQueryDto.SelectedExpressionDto;
import com.rorm.dto.dense.DenseSelectorDto;
import com.rorm.fetcher.Fetcher;
import com.rorm.mapper.DenseQueryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.*;


@SuppressWarnings("unused")
@Slf4j
@Component
@JournaledTool
@RequiredArgsConstructor
public class HypothesisVerificationTool {

    private final Fetcher fetcher;
    private final ObjectMapper objectMapper;
    private final DenseQueryMapper denseQueryMapper;

    // ── Pattern 1: SCREENING / MEDIATION ──

    @Tool(description = """
        SCREENING / MEDIATION — Tests whether mediator B screens the association between feature A and outcome.
        
        Mechanically computes corr(A, outcome), corr(A, B), corr(B, outcome), and derives
        partial_corr(A, outcome | B) using the standard formula.
        
        Verdict: SUPPORTED (full mediation) / INCOMPLETE (partial) / CONTRADICTED (no mediation path).""")
    public String verifyScreeningMediation(
        @ToolParam(description = "Root entity name") String rootName,
        @ToolParam(description = "Path to feature A") String featureA,
        @ToolParam(description = "Path to mediator B") String mediatorB,
        @ToolParam(description = "Path to outcome variable") String outcome,
        @ToolParam(description = "Generator's claimed effect size") double generatorNumber,
        @ToolParam(description = "Optional WHERE filter expression from generator's exploration") @Nullable DenseExpressionDto filter,
        ToolContext toolContext
    ) {
        try {
            var ctx = RormToolContext.from(toolContext);

            // Single query: CORR(A, outcome), CORR(A, B), CORR(B, outcome), COUNT(*)
            var query = buildQuery(rootName, filter,
                corr(featureA, outcome, "corr_a_outcome"),
                corr(featureA, mediatorB, "corr_a_b"),
                corr(mediatorB, outcome, "corr_b_outcome"),
                count("n")
            );
            var row = executeSingleRow(query, ctx);

            var corrAO = numVal(row, "corr_a_outcome");
            var corrAB = numVal(row, "corr_a_b");
            var corrBO = numVal(row, "corr_b_outcome");
            var n = longVal(row, "n");

            // partial_corr(A, outcome | B) = (rAO - rAB*rBO) / sqrt((1-rAB²)(1-rBO²))
            var numerator = corrAO - corrAB * corrBO;
            var denominator = Math.sqrt((1 - corrAB * corrAB) * (1 - corrBO * corrBO));
            var partialCorr = denominator < 1e-10 ? 0.0 : numerator / denominator;

            String verdict;
            var material = false;
            String executorNote;

            if (Math.abs(partialCorr) < 0.05 && Math.abs(corrAB) > 0.3) {
                verdict = "SUPPORTED";
                executorNote = "Full mediation confirmed. Condition on B instead of A.";
            } else if (Math.abs(partialCorr) < Math.abs(corrAO) && Math.abs(corrAB) > 0.3) {
                verdict = "INCOMPLETE";
                material = true;
                executorNote = String.format("Partial mediation. Residual: %.3f. Include both A and B.", partialCorr);
            } else if (Math.abs(corrAB) < 0.1) {
                verdict = "CONTRADICTED";
                material = true;
                executorNote = "No mediation path exists. B does not screen A.";
            } else {
                verdict = "CONDITIONAL";
                material = true;
                executorNote = String.format("Weak mediation. corr(A,O)=%.3f, partial=%.3f, corr(A,B)=%.3f",
                    corrAO, partialCorr, corrAB);
            }

            return formatVerification("SCREENING_MEDIATION", generatorNumber, partialCorr, verdict, material, executorNote,
                Map.of("corr_a_outcome", corrAO, "corr_a_b", corrAB, "corr_b_outcome", corrBO,
                    "partial_correlation", partialCorr, "n", n));
        } catch (Exception e) {
            log.error("Screening/mediation verification failed", e);
            return errorResponse("Verification failed: " + e.getMessage());
        }
    }

    // ── Pattern 2: PROXY ABSORPTION ──

    private DenseQueryDto buildQuery(String rootName, @Nullable DenseExpressionDto filter,
                                     SelectedExpressionDto... selections) {
        return new DenseQueryDto(rootName, "t", DenseSelectorDto.multi(Set.of(selections), false),
            null, filter, null, null, null, null, null);
    }

    // ── Pattern 3: TREATMENT DIRECTION ──

    private static SelectedExpressionDto corr(String yPath, String xPath, String alias) {
        return new SelectedExpressionDto(
            DenseExpressionDto.aggregation("CORR",
                List.of(DenseExpressionDto.path(yPath), DenseExpressionDto.path(xPath)), false),
            alias);
    }

    // ── Pattern 4: EFFECT MODIFIER ──

    private static SelectedExpressionDto count(String alias) {
        return new SelectedExpressionDto(
            DenseExpressionDto.aggregation("COUNT", List.of(), false),
            alias);
    }

    // ── Pattern 5: ABSENCE / BELOW DETECTION ──

    private Map<String, Object> executeSingleRow(DenseQueryDto query, RormToolContext ctx) {
        var results = executeQuery(query, ctx);
        if (results.isEmpty()) {
            throw new IllegalStateException("Query returned no results");
        }
        return results.getFirst();
    }

    // ── Pattern 6: ECOLOGICAL FALLACY ──

    private static double numVal(Map<String, Object> row, String key) {
        var value = row.get(key);
        if (value == null) {
            return 0.0;
        }
        if (value instanceof Number num) {
            return num.doubleValue();
        }
        throw new IllegalArgumentException("'" + key + "' is not numeric: " + value);
    }

    // ── Pattern 7: COLLIDER CONDITIONING ──

    private static long longVal(Map<String, Object> row, String key) {
        var value = row.get(key);
        if (value == null) {
            return 0L;
        }
        if (value instanceof Number num) {
            return num.longValue();
        }
        throw new IllegalArgumentException("'" + key + "' is not numeric: " + value);
    }

    // ── Pattern 8: SURVIVORSHIP BIAS ──

    private String formatVerification(String pattern, double generatorNumber, double skepticNumber,
                                      String verdict, boolean material, String executorNote,
                                      Map<String, Object> evidence) {
        try {
            var response = new VerificationResponse(true, pattern, generatorNumber, skepticNumber,
                skepticNumber - generatorNumber, verdict, material, executorNote, evidence, null);
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            return errorResponse("Failed to format verification: " + e.getMessage());
        }
    }

    // ── Pattern 9: TEMPORAL CONFOUNDING ──

    private String errorResponse(String message) {
        try {
            return objectMapper.writeValueAsString(
                new VerificationResponse(false, null, 0, 0, 0, null, false, null, null, message));
        } catch (JsonProcessingException e) {
            return "{\"success\":false,\"error\":\"" + message.replace("\"", "\\\"") + "\"}";
        }
    }

    // ── Pattern 11: SAMPLE SIZE ADEQUACY ──

    private List<Map<String, Object>> executeQuery(DenseQueryDto queryDTO, RormToolContext ctx) {
        var query = denseQueryMapper.toEntity(queryDTO, ctx.modelSpace());
        return fetcher.withSchema(ctx.schema(), () ->
            fetcher.queryForType(query, () -> {
                @SuppressWarnings("unchecked")
                var clazz = (Class<Map<String, Object>>) (Class<?>) Map.class;
                return clazz;
            })
        );
    }

    // ── Pattern 12: CONFOUNDER COMPLETENESS ──

    @Tool(description = """
        PROXY ABSORPTION — Tests whether categorical X absorbs continuous Y's signal on the outcome.
        
        Groups by X and computes CORR(Y, outcome) within each category.
        
        Verdict: SUPPORTED (absorption complete) / INCOMPLETE (partial) / CONTRADICTED (Y retains signal).""")
    public String verifyProxyAbsorption(
        @ToolParam(description = "Root entity name") String rootName,
        @ToolParam(description = "Path to categorical variable X") String categoricalX,
        @ToolParam(description = "Path to continuous variable Y") String continuousY,
        @ToolParam(description = "Path to outcome variable") String outcome,
        @ToolParam(description = "Optional WHERE filter expression") @Nullable DenseExpressionDto filter,
        ToolContext toolContext
    ) {
        try {
            var ctx = RormToolContext.from(toolContext);

            // GROUP BY X: CORR(Y, outcome), COUNT(*)
            var query = buildGroupedQuery(rootName, categoricalX, filter,
                corr(continuousY, outcome, "gradient"),
                count("n")
            );
            var results = executeQuery(query, ctx);

            var categoryGradients = new ArrayList<Map<String, Object>>();
            var significantCount = 0;
            var totalCategories = results.size();

            for (var row : results) {
                var gradient = numVal(row, "gradient");
                var n = longVal(row, "n");
                var category = String.valueOf(row.get("category"));
                var significant = Math.abs(gradient) > 0.1 && n >= 30;

                if (significant) {
                    significantCount++;
                }

                categoryGradients.add(Map.of(
                    "category", category, "gradient", gradient,
                    "n", n, "significant", significant));
            }

            String verdict;
            var material = false;
            String executorNote;

            if (significantCount == 0) {
                verdict = "SUPPORTED";
                executorNote = "Complete absorption confirmed. Use X instead of Y.";
            } else if (significantCount < totalCategories / 2) {
                verdict = "INCOMPLETE";
                material = true;
                executorNote = String.format("Partial absorption. %d/%d categories retain signal. Include interaction term.",
                    significantCount, totalCategories);
            } else {
                verdict = "CONTRADICTED";
                material = true;
                executorNote = String.format("No absorption. %d/%d categories show independent Y signal.",
                    significantCount, totalCategories);
            }

            return formatVerification("PROXY_ABSORPTION", 0.0,
                significantCount / (double) Math.max(totalCategories, 1),
                verdict, material, executorNote, Map.of("category_gradients", categoryGradients));
        } catch (Exception e) {
            log.error("Proxy absorption verification failed", e);
            return errorResponse("Verification failed: " + e.getMessage());
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  Mechanical query construction — builds DenseQueryDto from paths
    // ══════════════════════════════════════════════════════════════════

    private DenseQueryDto buildGroupedQuery(String rootName, String groupByPath,
                                            @Nullable DenseExpressionDto filter,
                                            SelectedExpressionDto... aggregations) {
        var groupExpr = DenseExpressionDto.path(groupByPath);
        var categorySelect = new SelectedExpressionDto(groupExpr, "category");

        var allSelections = new LinkedHashSet<SelectedExpressionDto>();
        allSelections.add(categorySelect);
        Collections.addAll(allSelections, aggregations);

        var groupBy = new DenseQueryDto.GroupByDto(List.of(groupExpr));
        return new DenseQueryDto(rootName, "t", DenseSelectorDto.multi(allSelections, false),
            null, filter, groupBy, null, null, null, null);
    }

    @Tool(description = """
        TREATMENT DIRECTION — Tests whether a treatment's effect direction holds across confounder strata.
        Detects Simpson's Paradox.
        
        Stratifies by confounder, computes AVG(outcome) per (stratum, treatment_level).
        
        Verdict: SUPPORTED / CONDITIONAL (Simpson's) / CONTRADICTED.""")
    public String verifyTreatmentDirection(
        @ToolParam(description = "Root entity name") String rootName,
        @ToolParam(description = "Path to treatment variable") String treatment,
        @ToolParam(description = "Path to outcome variable") String outcome,
        @ToolParam(description = "Path to confounder variable to stratify by") String confounder,
        @ToolParam(description = "'positive', 'negative', or 'null'") String claimedDirection,
        @ToolParam(description = "Minimum stratum size to consider") int minStratumSize,
        @ToolParam(description = "Optional WHERE filter expression") @Nullable DenseExpressionDto filter,
        ToolContext toolContext
    ) {
        try {
            var ctx = RormToolContext.from(toolContext);

            // GROUP BY confounder, treatment: AVG(outcome), COUNT(*)
            var query = buildDoubleGroupedQuery(rootName, confounder, treatment, filter,
                avg(outcome, "outcome_rate"), count("n"));
            var results = executeQuery(query, ctx);

            // Group by stratum
            var byStratum = new HashMap<String, List<Map<String, Object>>>();
            for (var row : results) {
                var stratum = String.valueOf(row.get("group1"));
                byStratum.computeIfAbsent(stratum, _ -> new ArrayList<>()).add(row);
            }

            var consistentStrata = 0;
            var flippedStrata = 0;
            var stratumEffects = new ArrayList<Map<String, Object>>();

            for (var entry : byStratum.entrySet()) {
                var stratumData = entry.getValue();
                if (stratumData.size() < 2) {
                    continue;
                }

                stratumData.sort(Comparator.comparing(m -> numVal(m, "group2")));

                var firstRate = numVal(stratumData.getFirst(), "outcome_rate");
                var lastRate = numVal(stratumData.getLast(), "outcome_rate");
                var totalN = stratumData.stream().mapToLong(m -> longVal(m, "n")).sum();

                if (totalN < minStratumSize) {
                    continue;
                }

                var effect = lastRate - firstRate;
                var observedDirection = effect > 0.02 ? "positive" : (effect < -0.02 ? "negative" : "null");
                var matches = observedDirection.equals(claimedDirection);

                if (matches) {
                    consistentStrata++;
                } else if (!observedDirection.equals("null")) {
                    flippedStrata++;
                }

                stratumEffects.add(Map.of(
                    "stratum", entry.getKey(), "effect", effect,
                    "direction", observedDirection, "matches_claim", matches, "n", totalN));
            }

            var totalValid = consistentStrata + flippedStrata;
            String verdict;
            var material = false;
            String executorNote;

            if (flippedStrata == 0 && consistentStrata > 0) {
                verdict = "SUPPORTED";
                executorNote = String.format("Direction holds in all %d strata.", consistentStrata);
            } else if (flippedStrata > 0 && flippedStrata < totalValid / 2) {
                verdict = "CONDITIONAL";
                material = true;
                executorNote = String.format("Simpson's Paradox. Direction flips in %d/%d strata.",
                    flippedStrata, totalValid);
            } else if (flippedStrata >= totalValid / 2) {
                verdict = "CONTRADICTED";
                material = true;
                executorNote = String.format("Direction reverses in %d/%d strata. Marginal effect confounded.",
                    flippedStrata, totalValid);
            } else {
                verdict = "INSUFFICIENT";
                material = true;
                executorNote = "No valid strata with sufficient sample size.";
            }

            return formatVerification("TREATMENT_DIRECTION", 0.0,
                totalValid > 0 ? consistentStrata / (double) totalValid : 0.0,
                verdict, material, executorNote, Map.of("stratum_effects", stratumEffects));
        } catch (Exception e) {
            log.error("Treatment direction verification failed", e);
            return errorResponse("Verification failed: " + e.getMessage());
        }
    }

    private DenseQueryDto buildDoubleGroupedQuery(String rootName, String group1Path, String group2Path,
                                                  @Nullable DenseExpressionDto filter,
                                                  SelectedExpressionDto... aggregations) {
        var g1 = DenseExpressionDto.path(group1Path);
        var g2 = DenseExpressionDto.path(group2Path);

        var allSelections = new LinkedHashSet<SelectedExpressionDto>();
        allSelections.add(new SelectedExpressionDto(g1, "group1"));
        allSelections.add(new SelectedExpressionDto(g2, "group2"));
        Collections.addAll(allSelections, aggregations);

        var groupBy = new DenseQueryDto.GroupByDto(List.of(g1, g2));
        return new DenseQueryDto(rootName, "t", DenseSelectorDto.multi(allSelections, false),
            null, filter, groupBy, null, null, null, null);
    }

    // ── Expression factories ──

    private static SelectedExpressionDto avg(String path, String alias) {
        return new SelectedExpressionDto(
            DenseExpressionDto.aggregation("AVG",
                List.of(DenseExpressionDto.path(path)), false),
            alias);
    }

    @Tool(description = """
        EFFECT MODIFIER — Tests whether variable M modifies the treatment effect.
        
        Computes REGR_SLOPE(outcome, treatment) within each M quartile and checks
        whether the slope varies >2x across strata.
        
        Verdict: EMPIRICALLY_SUPPORTED / PARTIALLY_SUPPORTED / UNSUPPORTED.""")
    public String verifyEffectModifier(
        @ToolParam(description = "Root entity name") String rootName,
        @ToolParam(description = "Path to treatment variable") String treatment,
        @ToolParam(description = "Path to outcome variable") String outcome,
        @ToolParam(description = "Path to modifier variable M") String modifier,
        @ToolParam(description = "Whether M appears in stability selection interaction candidates") boolean inInteractionCandidates,
        @ToolParam(description = "Optional WHERE filter expression") @Nullable DenseExpressionDto filter,
        ToolContext toolContext
    ) {
        try {
            var ctx = RormToolContext.from(toolContext);

            // GROUP BY modifier: REGR_SLOPE(outcome, treatment), COUNT(*)
            var query = buildGroupedQuery(rootName, modifier, filter,
                regrSlope(outcome, treatment, "treatment_effect"),
                count("n")
            );
            var results = executeQuery(query, ctx);

            if (results.isEmpty()) {
                return errorResponse("No strata returned");
            }

            var minEffect = Double.MAX_VALUE;
            var maxEffect = -Double.MAX_VALUE;
            var stratumEffects = new ArrayList<Map<String, Object>>();

            for (var row : results) {
                var effect = numVal(row, "treatment_effect");
                var n = longVal(row, "n");
                var stratum = String.valueOf(row.get("category"));

                if (n >= 30) {
                    minEffect = Math.min(minEffect, effect);
                    maxEffect = Math.max(maxEffect, effect);
                    stratumEffects.add(Map.of("stratum", stratum, "treatment_effect", effect, "n", n));
                }
            }

            if (stratumEffects.isEmpty()) {
                return errorResponse("No adequately powered strata (n >= 30)");
            }

            var varianceRatio = Math.abs(minEffect) < 1e-10 ? Double.MAX_VALUE : Math.abs(maxEffect / minEffect);

            String verdict;
            var material = false;
            String executorNote;

            if (varianceRatio > 2.0 && inInteractionCandidates) {
                verdict = "EMPIRICALLY_SUPPORTED";
                material = true;
                executorNote = String.format("Strong effect modification (%.2fx). Include interaction term.", varianceRatio);
            } else if (varianceRatio > 2.0) {
                verdict = "PARTIALLY_SUPPORTED";
                material = true;
                executorNote = String.format("Data shows modification (%.2fx) but SS didn't flag it.", varianceRatio);
            } else {
                verdict = "UNSUPPORTED";
                executorNote = String.format("Effect constant across strata (%.2fx). No interaction needed.", varianceRatio);
            }

            return formatVerification("EFFECT_MODIFIER", 0.0, varianceRatio, verdict, material, executorNote,
                Map.of("stratum_effects", stratumEffects, "variance_ratio", varianceRatio,
                    "min_effect", minEffect, "max_effect", maxEffect));
        } catch (Exception e) {
            log.error("Effect modifier verification failed", e);
            return errorResponse("Verification failed: " + e.getMessage());
        }
    }

    private static SelectedExpressionDto regrSlope(String yPath, String xPath, String alias) {
        return new SelectedExpressionDto(
            DenseExpressionDto.aggregation("REGR_SLOPE",
                List.of(DenseExpressionDto.path(yPath), DenseExpressionDto.path(xPath)), false),
            alias);
    }

    @Tool(description = """
        ABSENCE / BELOW DETECTION — Tests whether feature F truly has no signal after saturation.
        
        Computes CORR(F, outcome) and REGR_SLOPE(outcome, F) within a subpopulation where
        signal is domain-expected (filter MUST come from the generator's own exploration thresholds).
        
        Verdict: CONFIRMED_NULL / CONDITIONAL_SIGNAL_EXISTS / UNDERPOWERED.""")
    public String verifyAbsence(
        @ToolParam(description = "Root entity name") String rootName,
        @ToolParam(description = "Path to feature F") String feature,
        @ToolParam(description = "Path to outcome variable") String outcome,
        @ToolParam(description = "WHERE filter restricting to the expected-signal subpopulation (from generator's thresholds)") DenseExpressionDto subpopulationFilter,
        @ToolParam(description = "Human-readable description of the subpopulation filter") String subpopulationDefinition,
        ToolContext toolContext
    ) {
        try {
            var ctx = RormToolContext.from(toolContext);

            var query = buildQuery(rootName, subpopulationFilter,
                corr(feature, outcome, "gradient"),
                count("n")
            );
            var row = executeSingleRow(query, ctx);

            var gradient = numVal(row, "gradient");
            var n = longVal(row, "n");

            String verdict;
            var material = false;
            String executorNote;

            if (Math.abs(gradient) < 0.05) {
                verdict = "CONFIRMED_NULL";
                executorNote = String.format("No signal in expected subpopulation (n=%d). Safe to exclude.", n);
            } else if (n < 100) {
                verdict = "UNDERPOWERED";
                material = true;
                executorNote = String.format("Gradient %.3f but n=%d too small. Collect more data or pool.", gradient, n);
            } else {
                verdict = "CONDITIONAL_SIGNAL_EXISTS";
                material = true;
                executorNote = String.format("Signal exists: gradient=%.3f (n=%d). Filter: %s. Include conditional term.",
                    gradient, n, subpopulationDefinition);
            }

            return formatVerification("ABSENCE_BELOW_DETECTION", 0.0, gradient, verdict, material, executorNote,
                Map.of("gradient", gradient, "n", n, "subpopulation", subpopulationDefinition));
        } catch (Exception e) {
            log.error("Absence verification failed", e);
            return errorResponse("Verification failed: " + e.getMessage());
        }
    }

    @Tool(description = """
        ECOLOGICAL FALLACY — Compares between-group vs within-group effects.
        
        Computes the marginal REGR_SLOPE(outcome, feature) and the average within-group slope
        (grouped by the most granular grouping available). If within-group diverges from
        between-group, the marginal analysis suffers from ecological fallacy.
        
        Verdict: SUPPORTED / ECOLOGICAL.""")
    public String verifyEcologicalFallacy(
        @ToolParam(description = "Root entity name") String rootName,
        @ToolParam(description = "Path to feature variable") String feature,
        @ToolParam(description = "Path to outcome variable") String outcome,
        @ToolParam(description = "Path to grouping variable (most granular available)") String groupingVariable,
        @ToolParam(description = "Optional WHERE filter expression") @Nullable DenseExpressionDto filter,
        ToolContext toolContext
    ) {
        try {
            var ctx = RormToolContext.from(toolContext);

            // Between-group: marginal slope
            var betweenQuery = buildQuery(rootName, filter,
                regrSlope(outcome, feature, "between_group_effect"));
            var betweenRow = executeSingleRow(betweenQuery, ctx);
            var betweenEffect = numVal(betweenRow, "between_group_effect");

            // Within-group: AVG of per-group slopes
            var withinQuery = buildGroupedQuery(rootName, groupingVariable, filter,
                regrSlope(outcome, feature, "slope"),
                count("n")
            );
            var withinResults = executeQuery(withinQuery, ctx);

            // Weighted average of within-group slopes
            var totalWeight = 0L;
            var weightedSum = 0.0;
            var nGroups = 0;
            for (var row : withinResults) {
                var slope = numVal(row, "slope");
                var n = longVal(row, "n");
                if (n >= 10 && Double.isFinite(slope)) {
                    weightedSum += slope * n;
                    totalWeight += n;
                    nGroups++;
                }
            }
            var withinEffect = totalWeight > 0 ? weightedSum / totalWeight : 0.0;

            var delta = Math.abs(betweenEffect - withinEffect);
            var relativeDelta = Math.abs(betweenEffect) > 1e-10 ? delta / Math.abs(betweenEffect) : 0.0;

            String verdict;
            var material = false;
            String executorNote;

            if (relativeDelta < 0.2) {
                verdict = "SUPPORTED";
                executorNote = String.format("Within-group matches between-group (delta=%.1f%%). Marginal analysis valid.",
                    relativeDelta * 100);
            } else {
                verdict = "ECOLOGICAL";
                material = true;
                executorNote = String.format("Ecological fallacy. Between=%.3f, Within=%.3f (delta=%.1f%%, %d groups). Condition at within-group level.",
                    betweenEffect, withinEffect, relativeDelta * 100, nGroups);
            }

            return formatVerification("ECOLOGICAL_FALLACY", betweenEffect, withinEffect, verdict, material, executorNote,
                Map.of("between_group_effect", betweenEffect, "within_group_effect", withinEffect,
                    "delta", delta, "relative_delta", relativeDelta, "n_groups", nGroups));
        } catch (Exception e) {
            log.error("Ecological fallacy verification failed", e);
            return errorResponse("Verification failed: " + e.getMessage());
        }
    }

    // ── Query execution ──

    @Tool(description = """
        COLLIDER CONDITIONING — Tests whether conditioning on B induces a spurious correlation between
        treatment and confounder.
        
        Computes CORR(treatment, confounder) unconditionally, then CORR(treatment, confounder) within
        strata of B. If conditioning increases the correlation, B is likely a collider.
        
        Verdict: COLLIDER_WARNING / SAFE / NEUTRAL.""")
    public String verifyColliderConditioning(
        @ToolParam(description = "Root entity name") String rootName,
        @ToolParam(description = "Path to treatment variable") String treatment,
        @ToolParam(description = "Path to confounder variable") String confounderVar,
        @ToolParam(description = "Path to suspected collider variable B") String colliderB,
        @ToolParam(description = "Optional WHERE filter expression") @Nullable DenseExpressionDto filter,
        ToolContext toolContext
    ) {
        try {
            var ctx = RormToolContext.from(toolContext);

            // Unconditional: CORR(treatment, confounder)
            var uncondQuery = buildQuery(rootName, filter,
                corr(treatment, confounderVar, "unconditional_correlation"));
            var uncondRow = executeSingleRow(uncondQuery, ctx);
            var uncondCorr = numVal(uncondRow, "unconditional_correlation");

            // Conditional: CORR(treatment, confounder) grouped by B strata
            var condQuery = buildGroupedQuery(rootName, colliderB, filter,
                corr(treatment, confounderVar, "cond_corr"),
                count("n")
            );
            var condResults = executeQuery(condQuery, ctx);

            // Weighted average conditional correlation
            var totalWeight = 0L;
            var weightedSum = 0.0;
            for (var row : condResults) {
                var corrVal = numVal(row, "cond_corr");
                var n = longVal(row, "n");
                if (n >= 10 && Double.isFinite(corrVal)) {
                    weightedSum += corrVal * n;
                    totalWeight += n;
                }
            }
            var condCorr = totalWeight > 0 ? weightedSum / totalWeight : 0.0;

            var inducedAssociation = Math.abs(condCorr) - Math.abs(uncondCorr);

            String verdict;
            var material = false;
            String executorNote;

            if (inducedAssociation > 0.05) {
                verdict = "COLLIDER_WARNING";
                material = true;
                executorNote = String.format("Collider detected. Conditioning increases correlation by %.3f (%.3f -> %.3f). Do NOT condition on B.",
                    inducedAssociation, uncondCorr, condCorr);
            } else if (inducedAssociation < -0.05) {
                verdict = "SAFE";
                executorNote = String.format("Conditioning reduces correlation (%.3f -> %.3f). Safe to condition on B.",
                    uncondCorr, condCorr);
            } else {
                verdict = "NEUTRAL";
                executorNote = String.format("No meaningful change (%.3f -> %.3f). Conditioning on B is neutral.",
                    uncondCorr, condCorr);
            }

            return formatVerification("COLLIDER_CONDITIONING", uncondCorr, condCorr, verdict, material, executorNote,
                Map.of("unconditional_correlation", uncondCorr, "conditional_correlation", condCorr,
                    "induced_association", inducedAssociation));
        } catch (Exception e) {
            log.error("Collider conditioning verification failed", e);
            return errorResponse("Verification failed: " + e.getMessage());
        }
    }

    @Tool(description = """
        SURVIVORSHIP BIAS — Tests whether a weak/no effect is due to selective removal of high-risk units.
        
        Compares AVG(outcome) between active and retired/removed units at similar ages.
        The statusFlag attribute must partition units into two groups where one value marks
        retired/removed units.
        
        Verdict: SURVIVORSHIP_CONFIRMED / SURVIVORSHIP_UNLIKELY / UNDERPOWERED / UNTESTABLE.""")
    public String verifySurvivorshipBias(
        @ToolParam(description = "Root entity name") String rootName,
        @ToolParam(description = "Path to outcome variable") String outcome,
        @ToolParam(description = "Path to status flag that distinguishes active/retired") String statusFlag,
        @ToolParam(description = "The value of statusFlag that marks retired/removed units (e.g., 'retired', 'inactive', 'true')") String retiredValue,
        @ToolParam(description = "Optional WHERE filter expression") @Nullable DenseExpressionDto filter,
        ToolContext toolContext
    ) {
        try {
            var ctx = RormToolContext.from(toolContext);

            // GROUP BY statusFlag: AVG(outcome), COUNT(*)
            var query = buildGroupedQuery(rootName, statusFlag, filter,
                avg(outcome, "outcome_rate"), count("n"));
            var results = executeQuery(query, ctx);

            if (results.size() < 2) {
                return formatVerification("SURVIVORSHIP_BIAS", 0, 0, "UNTESTABLE", true,
                    "Insufficient data. Need both active and retired groups.", Map.of("results", results));
            }

            // Find retired vs active rows
            Double retiredRate = null, activeRate = null;
            long retiredN = 0, activeN = 0;
            for (var row : results) {
                var category = String.valueOf(row.get("category"));
                if (category.equals(retiredValue)) {
                    retiredRate = numVal(row, "outcome_rate");
                    retiredN = longVal(row, "n");
                } else if (activeRate == null || longVal(row, "n") > activeN) {
                    activeRate = numVal(row, "outcome_rate");
                    activeN = longVal(row, "n");
                }
            }

            if (retiredRate == null || activeRate == null) {
                return errorResponse("Could not find both retired ('" + retiredValue + "') and active groups");
            }

            var attenuation = retiredRate - activeRate;

            String verdict;
            var material = false;
            String executorNote;

            if (attenuation > 0.05 && retiredN >= 30) {
                verdict = "SURVIVORSHIP_CONFIRMED";
                material = true;
                executorNote = String.format("Survivorship bias detected. Retired units had %.1f%% higher outcome rate. Attenuation: %.3f.",
                    attenuation * 100, attenuation);
            } else if (Math.abs(attenuation) < 0.05) {
                verdict = "SURVIVORSHIP_UNLIKELY";
                executorNote = String.format("No survivorship bias. Similar outcomes (delta=%.3f).", attenuation);
            } else {
                verdict = "UNDERPOWERED";
                material = true;
                executorNote = String.format("Retired sample too small (n=%d). Cannot rule out survivorship.", retiredN);
            }

            return formatVerification("SURVIVORSHIP_BIAS", activeRate, retiredRate, verdict, material, executorNote,
                Map.of("retired_outcome_rate", retiredRate, "active_outcome_rate", activeRate,
                    "attenuation", attenuation, "retired_n", retiredN, "active_n", activeN));
        } catch (Exception e) {
            log.error("Survivorship bias verification failed", e);
            return errorResponse("Verification failed: " + e.getMessage());
        }
    }

    // ── Value extraction ──

    @Tool(description = """
        TEMPORAL CONFOUNDING — Tests whether a cohort effect is actually a calendar/period effect.
        
        Computes the overall REGR_SLOPE(outcome, cohort) and then the same slope within
        each time period. If within-period slopes reverse, the overall gradient is confounded by time.
        
        Verdict: SUPPORTED / TEMPORAL_CONFOUND / CONDITIONAL.""")
    public String verifyTemporalConfounding(
        @ToolParam(description = "Root entity name") String rootName,
        @ToolParam(description = "Path to cohort/vintage variable") String cohortVariable,
        @ToolParam(description = "Path to outcome variable") String outcome,
        @ToolParam(description = "Path to time period variable") String timePeriod,
        @ToolParam(description = "Optional WHERE filter expression") @Nullable DenseExpressionDto filter,
        ToolContext toolContext
    ) {
        try {
            var ctx = RormToolContext.from(toolContext);

            // Overall gradient
            var overallQuery = buildQuery(rootName, filter,
                regrSlope(outcome, cohortVariable, "overall_gradient"));
            var overallRow = executeSingleRow(overallQuery, ctx);
            var overallGradient = numVal(overallRow, "overall_gradient");

            // Within-period gradients
            var withinQuery = buildGroupedQuery(rootName, timePeriod, filter,
                regrSlope(outcome, cohortVariable, "within_gradient"),
                count("n")
            );
            var withinResults = executeQuery(withinQuery, ctx);

            var periodGradients = new ArrayList<Map<String, Object>>();
            var consistentPeriods = 0;
            var reversedPeriods = 0;

            for (var row : withinResults) {
                var period = String.valueOf(row.get("category"));
                var withinGradient = numVal(row, "within_gradient");
                var n = longVal(row, "n");
                var sameSign = (overallGradient * withinGradient) > 0;

                if (n >= 50) {
                    if (sameSign) {
                        consistentPeriods++;
                    } else if (Math.abs(withinGradient) > 0.05) {
                        reversedPeriods++;
                    }
                }

                periodGradients.add(Map.of("period", period, "gradient", withinGradient,
                    "n", n, "same_sign_as_overall", sameSign));
            }

            var totalValid = consistentPeriods + reversedPeriods;
            String verdict;
            var material = false;
            String executorNote;

            if (reversedPeriods == 0 && consistentPeriods > 0) {
                verdict = "SUPPORTED";
                executorNote = String.format("True cohort effect. Gradient consistent across %d periods.", consistentPeriods);
            } else if (reversedPeriods > totalValid / 2) {
                verdict = "TEMPORAL_CONFOUND";
                material = true;
                executorNote = String.format("Calendar effect. Gradient reverses in %d/%d periods. Overall (%.3f) confounded. Include time controls.",
                    reversedPeriods, totalValid, overallGradient);
            } else {
                verdict = "CONDITIONAL";
                material = true;
                executorNote = String.format("Mixed: %d consistent, %d reversed periods.", consistentPeriods, reversedPeriods);
            }

            return formatVerification("TEMPORAL_CONFOUNDING", overallGradient,
                totalValid > 0 ? consistentPeriods / (double) totalValid : 0.0,
                verdict, material, executorNote,
                Map.of("overall_gradient", overallGradient, "period_gradients", periodGradients,
                    "consistent_periods", consistentPeriods, "reversed_periods", reversedPeriods));
        } catch (Exception e) {
            log.error("Temporal confounding verification failed", e);
            return errorResponse("Verification failed: " + e.getMessage());
        }
    }

    @Tool(description = """
        SAMPLE SIZE ADEQUACY — Tests whether a stratified finding has adequate statistical power.
        
        Computes per-stratum: n, AVG(outcome) as effect proxy, base_rate approximation.
        Derives detectable effect size at each n using 16/(n*p*delta^2) and confidence interval width.
        
        Verdict: ADEQUATE / PARTIALLY_ADEQUATE / UNDERPOWERED.""")
    public String verifySampleSizeAdequacy(
        @ToolParam(description = "Root entity name") String rootName,
        @ToolParam(description = "Path to stratification variable") String stratumVariable,
        @ToolParam(description = "Path to outcome variable") String outcome,
        @ToolParam(description = "Minimum meaningful effect size (delta)") double minimumMeaningfulEffect,
        @ToolParam(description = "Optional WHERE filter expression") @Nullable DenseExpressionDto filter,
        ToolContext toolContext
    ) {
        try {
            var ctx = RormToolContext.from(toolContext);

            // GROUP BY stratum: AVG(outcome), STDDEV_POP(outcome), COUNT(*)
            var query = buildGroupedQuery(rootName, stratumVariable, filter,
                avg(outcome, "observed_effect"),
                stddevPop(outcome, "outcome_sd"),
                count("n")
            );
            var results = executeQuery(query, ctx);

            var stratumAdequacy = new ArrayList<Map<String, Object>>();
            var adequateStrata = 0;
            var underpoweredStrata = 0;

            for (var row : results) {
                var stratum = String.valueOf(row.get("category"));
                var n = longVal(row, "n");
                var observedEffect = numVal(row, "observed_effect");
                var baseRate = Math.max(0.01, Math.min(0.99, observedEffect));

                var detectableEffect = Math.sqrt(16.0 / (n * baseRate));
                var ciWidth = 1.96 * Math.sqrt(baseRate * (1 - baseRate) / n);
                var adequate = n >= 100 && detectableEffect <= minimumMeaningfulEffect;
                var effectAboveNoise = Math.abs(observedEffect) > ciWidth;

                if (adequate && effectAboveNoise) {
                    adequateStrata++;
                } else {
                    underpoweredStrata++;
                }

                stratumAdequacy.add(Map.of("stratum", stratum, "n", n,
                    "observed_effect", observedEffect, "detectable_effect", detectableEffect,
                    "ci_width", ciWidth, "adequate", adequate, "effect_above_noise", effectAboveNoise));
            }

            var totalStrata = adequateStrata + underpoweredStrata;
            String verdict;
            var material = false;
            String executorNote;

            if (underpoweredStrata == 0) {
                verdict = "ADEQUATE";
                executorNote = String.format("All %d strata have sufficient power.", adequateStrata);
            } else if (underpoweredStrata < totalStrata / 2) {
                verdict = "PARTIALLY_ADEQUATE";
                material = true;
                executorNote = String.format("%d/%d strata underpowered. Consider pooling weak strata.",
                    underpoweredStrata, totalStrata);
            } else {
                verdict = "UNDERPOWERED";
                material = true;
                executorNote = String.format("%d/%d strata underpowered. Finding unreliable.",
                    underpoweredStrata, totalStrata);
            }

            return formatVerification("SAMPLE_SIZE_ADEQUACY", minimumMeaningfulEffect,
                totalStrata > 0 ? adequateStrata / (double) totalStrata : 0.0,
                verdict, material, executorNote,
                Map.of("stratum_adequacy", stratumAdequacy,
                    "adequate_strata", adequateStrata, "underpowered_strata", underpoweredStrata));
        } catch (Exception e) {
            log.error("Sample size adequacy verification failed", e);
            return errorResponse("Verification failed: " + e.getMessage());
        }
    }

    // ── Response formatting ──

    private static SelectedExpressionDto stddevPop(String path, String alias) {
        return new SelectedExpressionDto(
            DenseExpressionDto.aggregation("STDDEV_POP",
                List.of(DenseExpressionDto.path(path)), false),
            alias);
    }

    @Tool(description = """
        CONFOUNDER COMPLETENESS — Tests whether a DAG edge (treatment -> outcome) is missing confounders.
        
        For each candidate variable: computes CORR(candidate, treatment) and CORR(candidate, outcome).
        If any unlisted variable correlates with both, the DAG is incomplete.
        
        Verdict: COMPLETE / MISSING_CONFOUNDER.""")
    public String verifyConfounderCompleteness(
        @ToolParam(description = "Root entity name") String rootName,
        @ToolParam(description = "Path to treatment variable") String treatment,
        @ToolParam(description = "Path to outcome variable") String outcome,
        @ToolParam(description = "Paths to candidate variables NOT already in the confounder set") List<String> candidateVariables,
        @ToolParam(description = "Minimum correlation threshold to flag a missing confounder") double correlationThreshold,
        @ToolParam(description = "Optional WHERE filter expression") @Nullable DenseExpressionDto filter,
        ToolContext toolContext
    ) {
        try {
            var ctx = RormToolContext.from(toolContext);
            var missingConfounders = new ArrayList<Map<String, Object>>();

            for (var candidate : candidateVariables) {
                var query = buildQuery(rootName, filter,
                    corr(candidate, treatment, "corr_treatment"),
                    corr(candidate, outcome, "corr_outcome"),
                    count("n")
                );
                var row = executeSingleRow(query, ctx);

                var corrTreatment = numVal(row, "corr_treatment");
                var corrOutcome = numVal(row, "corr_outcome");
                var n = longVal(row, "n");

                if (Math.abs(corrTreatment) >= correlationThreshold
                    && Math.abs(corrOutcome) >= correlationThreshold
                    && n >= 100) {
                    var biasDirection = (corrTreatment * corrOutcome > 0) ? "positive" : "negative";
                    missingConfounders.add(Map.of("variable", candidate,
                        "corr_with_treatment", corrTreatment, "corr_with_outcome", corrOutcome,
                        "bias_direction", biasDirection, "n", n));
                }
            }

            String verdict;
            var material = false;
            String executorNote;

            if (missingConfounders.isEmpty()) {
                verdict = "COMPLETE";
                executorNote = "No missing confounders detected among observed variables.";
            } else {
                verdict = "MISSING_CONFOUNDER";
                material = true;
                var strongest = missingConfounders.stream()
                    .max(Comparator.comparingDouble(m ->
                        Math.abs((double) m.get("corr_with_treatment")) * Math.abs((double) m.get("corr_with_outcome"))))
                    .orElseThrow();
                executorNote = String.format("Missing confounder(s). Strongest: %s (r_treat=%.3f, r_out=%.3f, bias=%s). Total: %d",
                    strongest.get("variable"), strongest.get("corr_with_treatment"),
                    strongest.get("corr_with_outcome"), strongest.get("bias_direction"),
                    missingConfounders.size());
            }

            return formatVerification("CONFOUNDER_COMPLETENESS", 0.0, missingConfounders.size(),
                verdict, material, executorNote, Map.of("missing_confounders", missingConfounders));
        } catch (Exception e) {
            log.error("Confounder completeness verification failed", e);
            return errorResponse("Verification failed: " + e.getMessage());
        }
    }

    public record VerificationResponse(
        boolean success,
        @Nullable String pattern,
        double generatorNumber,
        double skepticNumber,
        double delta,
        @Nullable String verdict,
        boolean material,
        @Nullable String executorNote,
        @Nullable Map<String, Object> evidence,
        @Nullable String error
    ) {}
}
