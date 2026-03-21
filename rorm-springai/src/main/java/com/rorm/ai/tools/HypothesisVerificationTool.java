package com.rorm.ai.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rorm.ai.JournaledTool;
import com.rorm.ai.RormToolContext;
import com.rorm.dto.dense.DenseExpressionDto;
import com.rorm.fetcher.Fetcher;
import com.rorm.mapper.DenseQueryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

import static com.rorm.ai.tools.VerificationQueryBuilder.*;
import static com.rorm.ai.tools.VerificationQueryExecutor.longVal;
import static com.rorm.ai.tools.VerificationQueryExecutor.numVal;


@SuppressWarnings("unused")
@Slf4j
@Component
@JournaledTool
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class HypothesisVerificationTool {

    private final VerificationQueryExecutor executor;
    private final VerificationResponseFormatter formatter;

    public HypothesisVerificationTool(Fetcher fetcher, ObjectMapper objectMapper, DenseQueryMapper denseQueryMapper) {
        this.executor = new VerificationQueryExecutor(fetcher, denseQueryMapper);
        this.formatter = new VerificationResponseFormatter(objectMapper);
    }

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
            var row = executor.executeSingle(query(rootName, filter,
                corr(featureA, outcome, "corr_a_outcome"),
                corr(featureA, mediatorB, "corr_a_b"),
                corr(mediatorB, outcome, "corr_b_outcome"),
                count("n")
            ), ctx);

            var corrAO = numVal(row, "corr_a_outcome");
            var corrAB = numVal(row, "corr_a_b");
            var corrBO = numVal(row, "corr_b_outcome");
            var n = longVal(row, "n");

            var partialCorr = partialCorrelation(corrAO, corrAB, corrBO);

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

            return formatter.format("SCREENING_MEDIATION", generatorNumber, partialCorr, verdict, material, executorNote,
                Map.of("corr_a_outcome", corrAO, "corr_a_b", corrAB, "corr_b_outcome", corrBO,
                    "partial_correlation", partialCorr, "n", n));
        } catch (Exception e) {
            log.error("Screening/mediation verification failed", e);
            return formatter.error("Verification failed: " + e.getMessage());
        }
    }

    private static double partialCorrelation(double corrAO, double corrAB, double corrBO) {
        var numerator = corrAO - corrAB * corrBO;
        var denominator = Math.sqrt((1 - corrAB * corrAB) * (1 - corrBO * corrBO));
        return denominator < 0.01 ? numerator : numerator / denominator;
    }

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
            var results = executor.execute(groupedQuery(rootName, categoricalX, filter,
                corr(continuousY, outcome, "gradient"), count("n")
            ), ctx);

            var categoryGradients = new ArrayList<Map<String, Object>>();
            var significantCount = 0;

            for (var row : results) {
                var gradient = numVal(row, "gradient");
                var n = longVal(row, "n");
                var category = String.valueOf(row.get("category"));
                var significant = Math.abs(gradient) > 0.1 && n >= 30;
                if (significant) {
                    significantCount++;
                }
                categoryGradients.add(Map.of("category", category, "gradient", gradient,
                    "n", n, "significant", significant));
            }

            var totalCategories = results.size();
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

            return formatter.format("PROXY_ABSORPTION", 0.0,
                significantCount / (double) Math.max(totalCategories, 1),
                verdict, material, executorNote, Map.of("category_gradients", categoryGradients));
        } catch (Exception e) {
            log.error("Proxy absorption verification failed", e);
            return formatter.error("Verification failed: " + e.getMessage());
        }
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
            var results = executor.execute(doubleGroupedQuery(rootName, confounder, treatment, filter,
                avg(outcome, "outcome_rate"), count("n")), ctx);

            var byStratum = new HashMap<String, List<Map<String, Object>>>();
            for (var row : results) {
                byStratum.computeIfAbsent(String.valueOf(row.get("group1")), _ -> new ArrayList<>()).add(row);
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

                stratumEffects.add(Map.of("stratum", entry.getKey(), "effect", effect,
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

            return formatter.format("TREATMENT_DIRECTION", 0.0,
                totalValid > 0 ? consistentStrata / (double) totalValid : 0.0,
                verdict, material, executorNote, Map.of("stratum_effects", stratumEffects));
        } catch (Exception e) {
            log.error("Treatment direction verification failed", e);
            return formatter.error("Verification failed: " + e.getMessage());
        }
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
            var results = executor.execute(groupedQuery(rootName, modifier, filter,
                regrSlope(outcome, treatment, "treatment_effect"), count("n")
            ), ctx);

            if (results.isEmpty()) {
                return formatter.error("No strata returned");
            }

            var minEffect = Double.MAX_VALUE;
            var maxEffect = -Double.MAX_VALUE;
            var stratumEffects = new ArrayList<Map<String, Object>>();

            for (var row : results) {
                var effect = numVal(row, "treatment_effect");
                var n = longVal(row, "n");
                if (n >= 30) {
                    minEffect = Math.min(minEffect, effect);
                    maxEffect = Math.max(maxEffect, effect);
                    stratumEffects.add(Map.of("stratum", String.valueOf(row.get("category")),
                        "treatment_effect", effect, "n", n));
                }
            }

            if (stratumEffects.isEmpty()) {
                return formatter.error("No adequately powered strata (n >= 30)");
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

            return formatter.format("EFFECT_MODIFIER", 0.0, varianceRatio, verdict, material, executorNote,
                Map.of("stratum_effects", stratumEffects, "variance_ratio", varianceRatio,
                    "min_effect", minEffect, "max_effect", maxEffect));
        } catch (Exception e) {
            log.error("Effect modifier verification failed", e);
            return formatter.error("Verification failed: " + e.getMessage());
        }
    }

    @Tool(description = """
        ABSENCE / BELOW DETECTION — Tests whether feature F truly has no signal after saturation.
        
        Computes CORR(F, outcome) within a subpopulation where signal is domain-expected
        (filter MUST come from the generator's own exploration thresholds).
        
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
            var row = executor.executeSingle(query(rootName, subpopulationFilter,
                corr(feature, outcome, "gradient"), count("n")
            ), ctx);

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

            return formatter.format("ABSENCE_BELOW_DETECTION", 0.0, gradient, verdict, material, executorNote,
                Map.of("gradient", gradient, "n", n, "subpopulation", subpopulationDefinition));
        } catch (Exception e) {
            log.error("Absence verification failed", e);
            return formatter.error("Verification failed: " + e.getMessage());
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

            var betweenEffect = numVal(
                executor.executeSingle(query(rootName, filter,
                    regrSlope(outcome, feature, "between_group_effect")), ctx),
                "between_group_effect");

            var withinResults = executor.execute(groupedQuery(rootName, groupingVariable, filter,
                regrSlope(outcome, feature, "slope"), count("n")
            ), ctx);

            var withinEffect = weightedAverageSlope(withinResults);
            var nGroups = (int) withinResults.stream()
                .filter(r -> longVal(r, "n") >= 10 && Double.isFinite(numVal(r, "slope")))
                .count();

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

            return formatter.format("ECOLOGICAL_FALLACY", betweenEffect, withinEffect, verdict, material, executorNote,
                Map.of("between_group_effect", betweenEffect, "within_group_effect", withinEffect,
                    "delta", delta, "relative_delta", relativeDelta, "n_groups", nGroups));
        } catch (Exception e) {
            log.error("Ecological fallacy verification failed", e);
            return formatter.error("Verification failed: " + e.getMessage());
        }
    }

    private static double weightedAverageSlope(List<Map<String, Object>> rows) {
        var totalWeight = 0L;
        var weightedSum = 0.0;
        for (var row : rows) {
            var slope = numVal(row, "slope");
            var n = longVal(row, "n");
            if (n >= 10 && Double.isFinite(slope)) {
                weightedSum += slope * n;
                totalWeight += n;
            }
        }
        return totalWeight > 0 ? weightedSum / totalWeight : 0.0;
    }

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

            var uncondCorr = numVal(
                executor.executeSingle(query(rootName, filter,
                    corr(treatment, confounderVar, "unconditional_correlation")), ctx),
                "unconditional_correlation");

            var condResults = executor.execute(groupedQuery(rootName, colliderB, filter,
                corr(treatment, confounderVar, "cond_corr"), count("n")
            ), ctx);

            var condCorr = weightedAverageCorrelation(condResults, "cond_corr");
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

            return formatter.format("COLLIDER_CONDITIONING", uncondCorr, condCorr, verdict, material, executorNote,
                Map.of("unconditional_correlation", uncondCorr, "conditional_correlation", condCorr,
                    "induced_association", inducedAssociation));
        } catch (Exception e) {
            log.error("Collider conditioning verification failed", e);
            return formatter.error("Verification failed: " + e.getMessage());
        }
    }

    private static double weightedAverageCorrelation(List<Map<String, Object>> rows, String corrKey) {
        var totalWeight = 0L;
        var weightedSum = 0.0;
        for (var row : rows) {
            var corrVal = numVal(row, corrKey);
            var n = longVal(row, "n");
            if (n >= 10 && Double.isFinite(corrVal)) {
                weightedSum += corrVal * n;
                totalWeight += n;
            }
        }
        return totalWeight > 0 ? weightedSum / totalWeight : 0.0;
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
            var results = executor.execute(groupedQuery(rootName, statusFlag, filter,
                avg(outcome, "outcome_rate"), count("n")), ctx);

            if (results.size() < 2) {
                return formatter.format("SURVIVORSHIP_BIAS", 0, 0, "UNTESTABLE", true,
                    "Insufficient data. Need both active and retired groups.", Map.of("results", results));
            }

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
                return formatter.error("Could not find both retired ('" + retiredValue + "') and active groups");
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

            return formatter.format("SURVIVORSHIP_BIAS", activeRate, retiredRate, verdict, material, executorNote,
                Map.of("retired_outcome_rate", retiredRate, "active_outcome_rate", activeRate,
                    "attenuation", attenuation, "retired_n", retiredN, "active_n", activeN));
        } catch (Exception e) {
            log.error("Survivorship bias verification failed", e);
            return formatter.error("Verification failed: " + e.getMessage());
        }
    }

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

            var overallGradient = numVal(
                executor.executeSingle(query(rootName, filter,
                    regrSlope(outcome, cohortVariable, "overall_gradient")), ctx),
                "overall_gradient");

            var withinResults = executor.execute(groupedQuery(rootName, timePeriod, filter,
                regrSlope(outcome, cohortVariable, "within_gradient"), count("n")
            ), ctx);

            var periodGradients = new ArrayList<Map<String, Object>>();
            var consistentPeriods = 0;
            var reversedPeriods = 0;

            for (var row : withinResults) {
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

                periodGradients.add(Map.of("period", String.valueOf(row.get("category")),
                    "gradient", withinGradient, "n", n, "same_sign_as_overall", sameSign));
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

            return formatter.format("TEMPORAL_CONFOUNDING", overallGradient,
                totalValid > 0 ? consistentPeriods / (double) totalValid : 0.0,
                verdict, material, executorNote,
                Map.of("overall_gradient", overallGradient, "period_gradients", periodGradients,
                    "consistent_periods", consistentPeriods, "reversed_periods", reversedPeriods));
        } catch (Exception e) {
            log.error("Temporal confounding verification failed", e);
            return formatter.error("Verification failed: " + e.getMessage());
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
            var results = executor.execute(groupedQuery(rootName, stratumVariable, filter,
                avg(outcome, "observed_effect"), stddevPop(outcome, "outcome_sd"), count("n")
            ), ctx);

            var stratumAdequacy = new ArrayList<Map<String, Object>>();
            var adequateStrata = 0;
            var underpoweredStrata = 0;

            for (var row : results) {
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

                stratumAdequacy.add(Map.of("stratum", String.valueOf(row.get("category")), "n", n,
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

            return formatter.format("SAMPLE_SIZE_ADEQUACY", minimumMeaningfulEffect,
                totalStrata > 0 ? adequateStrata / (double) totalStrata : 0.0,
                verdict, material, executorNote,
                Map.of("stratum_adequacy", stratumAdequacy,
                    "adequate_strata", adequateStrata, "underpowered_strata", underpoweredStrata));
        } catch (Exception e) {
            log.error("Sample size adequacy verification failed", e);
            return formatter.error("Verification failed: " + e.getMessage());
        }
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
                var row = executor.executeSingle(query(rootName, filter,
                    corr(candidate, treatment, "corr_treatment"),
                    corr(candidate, outcome, "corr_outcome"),
                    count("n")
                ), ctx);

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

            return formatter.format("CONFOUNDER_COMPLETENESS", 0.0, missingConfounders.size(),
                verdict, material, executorNote, Map.of("missing_confounders", missingConfounders));
        } catch (Exception e) {
            log.error("Confounder completeness verification failed", e);
            return formatter.error("Verification failed: " + e.getMessage());
        }
    }
}
