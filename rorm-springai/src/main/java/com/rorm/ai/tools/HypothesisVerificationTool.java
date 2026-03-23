package com.rorm.ai.tools;

import com.rorm.ai.JournaledTool;
import com.rorm.ai.RormToolContext;
import com.rorm.ai.tools.VerificationResponseFormatter.Verdict;
import com.rorm.dto.dense.DenseExpressionDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.*;

import static com.rorm.ai.tools.VerificationQueryBuilder.*;
import static com.rorm.ai.tools.VerificationQueryExecutor.longVal;
import static com.rorm.ai.tools.VerificationQueryExecutor.numVal;

@SuppressWarnings("unused")
@Slf4j
@Component
@JournaledTool
@RequiredArgsConstructor
public class HypothesisVerificationTool {

    private static final String EXPR_HINT = "Expression (path for column, or derived e.g. function/binary). " +
                                            "Paths resolve against the FROM root.";

    private final VerificationQueryExecutor executor;
    private final VerificationResponseFormatter formatter;

    @Tool(description = """
        SCREENING / MEDIATION — Tests whether mediator B screens the association between feature A and outcome.

        Mechanically computes corr(A, outcome), corr(A, B), corr(B, outcome), and derives
        partial_corr(A, outcome | B) using the standard formula.

        Verdict: SUPPORTED (full mediation) / INCOMPLETE (partial) / CONTRADICTED (no mediation path).""")
    public String verifyScreeningMediation(
        @ToolParam(description = "Root entity name") String rootName,
        @ToolParam(description = "Feature A. " + EXPR_HINT) DenseExpressionDto featureA,
        @ToolParam(description = "Mediator B. " + EXPR_HINT) DenseExpressionDto mediatorB,
        @ToolParam(description = "Outcome variable. " + EXPR_HINT) DenseExpressionDto outcome,
        @ToolParam(description = "Generator's claimed effect size") double generatorNumber,
        @ToolParam(description = "Optional WHERE filter from generator's exploration") @Nullable DenseExpressionDto filter,
        ToolContext toolContext
    ) {
        try {
            var ctx = RormToolContext.from(toolContext);
            requireCorrCompatible(ctx, rootName, featureA, "featureA", mediatorB, "mediatorB", outcome, "outcome");
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

            var verdict = Verdict.rules()
                .supported(Math.abs(partialCorr) < 0.05 && Math.abs(corrAB) > 0.3,
                    "Full mediation confirmed. Condition on B instead of A.")
                .when(Math.abs(partialCorr) < Math.abs(corrAO) && Math.abs(corrAB) > 0.3,
                    "INCOMPLETE", "Partial mediation. Residual: %.3f. Include both A and B.".formatted(partialCorr))
                .when(Math.abs(corrAB) < 0.1,
                    "CONTRADICTED", "No mediation path exists. B does not screen A.")
                .orElse("CONDITIONAL", "Weak mediation. corr(A,O)=%.3f, partial=%.3f, corr(A,B)=%.3f"
                    .formatted(corrAO, partialCorr, corrAB));

            return formatter.format("SCREENING_MEDIATION", generatorNumber, partialCorr, verdict,
                Map.of("corr_a_outcome", corrAO, "corr_a_b", corrAB, "corr_b_outcome", corrBO,
                    "partial_correlation", partialCorr, "n", n));
        } catch (Exception e) {
            log.error("Screening/mediation verification failed", e);
            return formatter.error("Verification failed: " + e.getMessage());
        }
    }

    private void requireCorrCompatible(RormToolContext ctx, String rootName, Object... pairs) {
        for (var i = 0; i < pairs.length; i += 2) {
            executor.requireCorrCompatible((DenseExpressionDto) pairs[i], (String) pairs[i + 1], rootName, ctx);
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
        @ToolParam(description = "Categorical variable X to group by. " + EXPR_HINT) DenseExpressionDto categoricalX,
        @ToolParam(description = "Continuous variable Y. " + EXPR_HINT) DenseExpressionDto continuousY,
        @ToolParam(description = "Outcome variable. " + EXPR_HINT) DenseExpressionDto outcome,
        @ToolParam(description = "Optional WHERE filter") @Nullable DenseExpressionDto filter,
        ToolContext toolContext
    ) {
        try {
            var ctx = RormToolContext.from(toolContext);
            requireCorrCompatible(ctx, rootName, continuousY, "continuousY", outcome, "outcome");
            var results = executor.execute(groupedQuery(rootName, categoricalX, filter,
                corr(continuousY, outcome, "gradient"), count("n")
            ), ctx);

            var categoryGradients = new ArrayList<Map<String, Object>>();
            var significantCount = 0;
            for (var row : results) {
                var gradient = numVal(row, "gradient");
                var n = longVal(row, "n");
                var significant = Math.abs(gradient) > 0.1 && n >= 30;
                if (significant) {
                    significantCount++;
                }
                categoryGradients.add(Map.of("category", String.valueOf(row.get("category")),
                    "gradient", gradient, "n", n, "significant", significant));
            }

            var total = results.size();
            var verdict = Verdict.rules()
                .supported(significantCount == 0,
                    "Complete absorption confirmed. Use X instead of Y.")
                .when(significantCount < total / 2,
                    "INCOMPLETE", "%d/%d categories retain signal. Include interaction term."
                        .formatted(significantCount, total))
                .orElse("CONTRADICTED", "No absorption. %d/%d categories show independent Y signal."
                    .formatted(significantCount, total));

            return formatter.format("PROXY_ABSORPTION", 0.0,
                significantCount / (double) Math.max(total, 1),
                verdict, Map.of("category_gradients", categoryGradients));
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
        @ToolParam(description = "Treatment variable. " + EXPR_HINT) DenseExpressionDto treatment,
        @ToolParam(description = "Outcome variable. " + EXPR_HINT) DenseExpressionDto outcome,
        @ToolParam(description = "Confounder to stratify by. " + EXPR_HINT) DenseExpressionDto confounder,
        @ToolParam(description = "'positive', 'negative', or 'null'") String claimedDirection,
        @ToolParam(description = "Minimum stratum size to consider") int minStratumSize,
        @ToolParam(description = "Optional WHERE filter") @Nullable DenseExpressionDto filter,
        ToolContext toolContext
    ) {
        try {
            var ctx = RormToolContext.from(toolContext);
            requireCorrCompatible(ctx, rootName, outcome, "outcome");
            var results = executor.execute(doubleGroupedQuery(rootName, confounder, treatment, filter,
                avg(outcome, "outcome_rate"), count("n")), ctx);

            var stratumEffects = computeStratumEffects(results, claimedDirection, minStratumSize);
            var consistent = (int) stratumEffects.stream().filter(e -> (boolean) e.get("matches_claim")).count();
            var flipped = (int) stratumEffects.stream()
                .filter(e -> !(boolean) e.get("matches_claim") && !"null".equals(e.get("direction")))
                .count();
            var totalValid = consistent + flipped;

            var verdict = Verdict.rules()
                .supported(flipped == 0 && consistent > 0,
                    "Direction holds in all %d strata.".formatted(consistent))
                .when(flipped > 0 && flipped < totalValid / 2,
                    "CONDITIONAL", "Simpson's Paradox. Direction flips in %d/%d strata."
                        .formatted(flipped, totalValid))
                .when(flipped >= totalValid / 2,
                    "CONTRADICTED", "Direction reverses in %d/%d strata. Marginal effect confounded."
                        .formatted(flipped, totalValid))
                .orElse("INSUFFICIENT", "No valid strata with sufficient sample size.");

            return formatter.format("TREATMENT_DIRECTION", 0.0,
                totalValid > 0 ? consistent / (double) totalValid : 0.0,
                verdict, Map.of("stratum_effects", stratumEffects));
        } catch (Exception e) {
            log.error("Treatment direction verification failed", e);
            return formatter.error("Verification failed: " + e.getMessage());
        }
    }

    private static List<Map<String, Object>> computeStratumEffects(
        List<Map<String, Object>> rows, String claimedDirection, int minStratumSize
    ) {
        var byStratum = new HashMap<String, List<Map<String, Object>>>();
        for (var row : rows) {
            byStratum.computeIfAbsent(String.valueOf(row.get("group1")), _ -> new ArrayList<>()).add(row);
        }

        var effects = new ArrayList<Map<String, Object>>();
        for (var entry : byStratum.entrySet()) {
            var stratumData = entry.getValue();
            if (stratumData.size() < 2) {
                continue;
            }
            stratumData.sort(Comparator.comparing(m -> numVal(m, "group2")));
            var totalN = stratumData.stream().mapToLong(m -> longVal(m, "n")).sum();
            if (totalN < minStratumSize) {
                continue;
            }

            var effect = numVal(stratumData.getLast(), "outcome_rate") - numVal(stratumData.getFirst(), "outcome_rate");
            var direction = classifyDirection(effect);
            effects.add(Map.of("stratum", entry.getKey(), "effect", effect,
                "direction", direction, "matches_claim", direction.equals(claimedDirection), "n", totalN));
        }
        return effects;
    }

    private static String classifyDirection(double effect) {
        return effect > 0.02 ? "positive" : (effect < -0.02 ? "negative" : "null");
    }

    @Tool(description = """
        EFFECT MODIFIER — Tests whether variable M modifies the treatment effect.

        Computes REGR_SLOPE(outcome, treatment) within each M quartile and checks
        whether the slope varies >2x across strata.

        Verdict: EMPIRICALLY_SUPPORTED / PARTIALLY_SUPPORTED / UNSUPPORTED.""")
    public String verifyEffectModifier(
        @ToolParam(description = "Root entity name") String rootName,
        @ToolParam(description = "Treatment variable. " + EXPR_HINT) DenseExpressionDto treatment,
        @ToolParam(description = "Outcome variable. " + EXPR_HINT) DenseExpressionDto outcome,
        @ToolParam(description = "Modifier variable M. " + EXPR_HINT) DenseExpressionDto modifier,
        @ToolParam(description = "Whether M appears in stability selection interaction candidates") boolean inInteractionCandidates,
        @ToolParam(description = "Optional WHERE filter") @Nullable DenseExpressionDto filter,
        ToolContext toolContext
    ) {
        try {
            var ctx = RormToolContext.from(toolContext);
            requireCorrCompatible(ctx, rootName, treatment, "treatment", outcome, "outcome");
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

            var ratio = Math.abs(minEffect) < 1e-10 ? Double.MAX_VALUE : Math.abs(maxEffect / minEffect);
            var verdict = Verdict.rules()
                .when(ratio > 2.0 && inInteractionCandidates,
                    "EMPIRICALLY_SUPPORTED", "Strong effect modification (%.2fx). Include interaction term.".formatted(ratio))
                .when(ratio > 2.0,
                    "PARTIALLY_SUPPORTED", "Data shows modification (%.2fx) but SS didn't flag it.".formatted(ratio))
                .orElse("UNSUPPORTED", false, "Effect constant across strata (%.2fx). No interaction needed.".formatted(ratio));

            return formatter.format("EFFECT_MODIFIER", 0.0, ratio, verdict,
                Map.of("stratum_effects", stratumEffects, "variance_ratio", ratio,
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
        @ToolParam(description = "Feature F. " + EXPR_HINT) DenseExpressionDto feature,
        @ToolParam(description = "Outcome variable. " + EXPR_HINT) DenseExpressionDto outcome,
        @ToolParam(description = "WHERE filter restricting to expected-signal subpopulation (from generator's thresholds)") DenseExpressionDto subpopulationFilter,
        @ToolParam(description = "Human-readable description of the subpopulation filter") String subpopulationDefinition,
        ToolContext toolContext
    ) {
        try {
            var ctx = RormToolContext.from(toolContext);
            requireCorrCompatible(ctx, rootName, feature, "feature", outcome, "outcome");
            var row = executor.executeSingle(query(rootName, subpopulationFilter,
                corr(feature, outcome, "gradient"), count("n")
            ), ctx);

            var gradient = numVal(row, "gradient");
            var n = longVal(row, "n");

            var verdict = Verdict.rules()
                .when(Math.abs(gradient) < 0.05,
                    "CONFIRMED_NULL", false, "No signal in expected subpopulation (n=%d). Safe to exclude.".formatted(n))
                .when(n < 100,
                    "UNDERPOWERED", "Gradient %.3f but n=%d too small. Collect more data or pool.".formatted(gradient, n))
                .orElse("CONDITIONAL_SIGNAL_EXISTS",
                    "Signal exists: gradient=%.3f (n=%d). Filter: %s. Include conditional term."
                        .formatted(gradient, n, subpopulationDefinition));

            return formatter.format("ABSENCE_BELOW_DETECTION", 0.0, gradient, verdict,
                Map.of("gradient", gradient, "n", n, "subpopulation", subpopulationDefinition));
        } catch (Exception e) {
            log.error("Absence verification failed", e);
            return formatter.error("Verification failed: " + e.getMessage());
        }
    }

    @Tool(description = """
        ECOLOGICAL FALLACY — Compares between-group vs within-group effects.

        Computes the marginal REGR_SLOPE(outcome, feature) and the average within-group slope.
        If within-group diverges from between-group, the marginal analysis suffers from ecological fallacy.

        Verdict: SUPPORTED / ECOLOGICAL.""")
    public String verifyEcologicalFallacy(
        @ToolParam(description = "Root entity name") String rootName,
        @ToolParam(description = "Feature variable. " + EXPR_HINT) DenseExpressionDto feature,
        @ToolParam(description = "Outcome variable. " + EXPR_HINT) DenseExpressionDto outcome,
        @ToolParam(description = "Grouping variable (most granular available). " + EXPR_HINT) DenseExpressionDto groupingVariable,
        @ToolParam(description = "Optional WHERE filter") @Nullable DenseExpressionDto filter,
        ToolContext toolContext
    ) {
        try {
            var ctx = RormToolContext.from(toolContext);
            requireCorrCompatible(ctx, rootName, feature, "feature", outcome, "outcome");

            var betweenEffect = numVal(executor.executeSingle(
                    query(rootName, filter, regrSlope(outcome, feature, "between_group_effect")), ctx),
                "between_group_effect");

            var withinResults = executor.execute(groupedQuery(rootName, groupingVariable, filter,
                regrSlope(outcome, feature, "slope"), count("n")
            ), ctx);

            var withinEffect = StatisticalPrimitives.weightedAverage(withinResults, "slope", 10);
            var nGroups = (int) withinResults.stream()
                .filter(r -> longVal(r, "n") >= 10 && Double.isFinite(numVal(r, "slope")))
                .count();
            var delta = Math.abs(betweenEffect - withinEffect);
            var relativeDelta = Math.abs(betweenEffect) > 1e-10 ? delta / Math.abs(betweenEffect) : 0.0;

            var verdict = Verdict.rules()
                .supported(relativeDelta < 0.2,
                    "Within-group matches between-group (delta=%.1f%%). Marginal analysis valid."
                        .formatted(relativeDelta * 100))
                .orElse("ECOLOGICAL",
                    "Ecological fallacy. Between=%.3f, Within=%.3f (delta=%.1f%%, %d groups). Condition at within-group level."
                        .formatted(betweenEffect, withinEffect, relativeDelta * 100, nGroups));

            return formatter.format("ECOLOGICAL_FALLACY", betweenEffect, withinEffect, verdict,
                Map.of("between_group_effect", betweenEffect, "within_group_effect", withinEffect,
                    "delta", delta, "relative_delta", relativeDelta, "n_groups", nGroups));
        } catch (Exception e) {
            log.error("Ecological fallacy verification failed", e);
            return formatter.error("Verification failed: " + e.getMessage());
        }
    }

    @Tool(description = """
        COLLIDER CONDITIONING — Tests whether conditioning on B induces spurious correlation.

        Computes CORR(treatment, confounder) unconditionally, then within strata of B.
        If conditioning increases the correlation, B is likely a collider.

        Verdict: COLLIDER_WARNING / SAFE / NEUTRAL.""")
    public String verifyColliderConditioning(
        @ToolParam(description = "Root entity name") String rootName,
        @ToolParam(description = "Treatment variable. " + EXPR_HINT) DenseExpressionDto treatment,
        @ToolParam(description = "Confounder variable. " + EXPR_HINT) DenseExpressionDto confounderVar,
        @ToolParam(description = "Suspected collider B. " + EXPR_HINT) DenseExpressionDto colliderB,
        @ToolParam(description = "Optional WHERE filter") @Nullable DenseExpressionDto filter,
        ToolContext toolContext
    ) {
        try {
            var ctx = RormToolContext.from(toolContext);
            requireCorrCompatible(ctx, rootName, treatment, "treatment", confounderVar, "confounderVar");

            var uncondCorr = numVal(executor.executeSingle(
                    query(rootName, filter, corr(treatment, confounderVar, "unconditional_correlation")), ctx),
                "unconditional_correlation");

            var condResults = executor.execute(groupedQuery(rootName, colliderB, filter,
                corr(treatment, confounderVar, "cond_corr"), count("n")
            ), ctx);
            var condCorr = StatisticalPrimitives.weightedAverage(condResults, "cond_corr", 10);
            var induced = Math.abs(condCorr) - Math.abs(uncondCorr);

            var verdict = Verdict.rules()
                .when(induced > 0.05,
                    "COLLIDER_WARNING", "Collider detected. Conditioning increases correlation by %.3f (%.3f -> %.3f). Do NOT condition on B."
                        .formatted(induced, uncondCorr, condCorr))
                .when(induced < -0.05,
                    "SAFE", false, "Conditioning reduces correlation (%.3f -> %.3f). Safe to condition on B."
                        .formatted(uncondCorr, condCorr))
                .orElse("NEUTRAL", false, "No meaningful change (%.3f -> %.3f). Conditioning on B is neutral."
                    .formatted(uncondCorr, condCorr));

            return formatter.format("COLLIDER_CONDITIONING", uncondCorr, condCorr, verdict,
                Map.of("unconditional_correlation", uncondCorr, "conditional_correlation", condCorr,
                    "induced_association", induced));
        } catch (Exception e) {
            log.error("Collider conditioning verification failed", e);
            return formatter.error("Verification failed: " + e.getMessage());
        }
    }

    @Tool(description = """
        SURVIVORSHIP BIAS — Tests whether a weak/no effect is due to selective removal of high-risk units.

        Compares AVG(outcome) between active and retired/removed units.

        Verdict: SURVIVORSHIP_CONFIRMED / SURVIVORSHIP_UNLIKELY / UNDERPOWERED / UNTESTABLE.""")
    public String verifySurvivorshipBias(
        @ToolParam(description = "Root entity name") String rootName,
        @ToolParam(description = "Outcome variable. " + EXPR_HINT) DenseExpressionDto outcome,
        @ToolParam(description = "Status flag distinguishing active/retired. " + EXPR_HINT) DenseExpressionDto statusFlag,
        @ToolParam(description = "Value of statusFlag marking retired units (e.g. 'retired', 'true')") String retiredValue,
        @ToolParam(description = "Optional WHERE filter") @Nullable DenseExpressionDto filter,
        ToolContext toolContext
    ) {
        try {
            var ctx = RormToolContext.from(toolContext);
            requireCorrCompatible(ctx, rootName, outcome, "outcome");
            var results = executor.execute(groupedQuery(rootName, statusFlag, filter,
                avg(outcome, "outcome_rate"), count("n")), ctx);

            if (results.size() < 2) {
                var verdict = new Verdict("UNTESTABLE", true,
                    "Insufficient data. Need both active and retired groups.");
                return formatter.format("SURVIVORSHIP_BIAS", 0, 0, verdict, Map.of("results", results));
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
            var verdict = Verdict.rules()
                .when(attenuation > 0.05 && retiredN >= 30,
                    "SURVIVORSHIP_CONFIRMED", "Survivorship bias detected. Retired units had %.1f%% higher outcome rate. Attenuation: %.3f."
                        .formatted(attenuation * 100, attenuation))
                .when(Math.abs(attenuation) < 0.05,
                    "SURVIVORSHIP_UNLIKELY", false, "No survivorship bias. Similar outcomes (delta=%.3f).".formatted(attenuation))
                .orElse("UNDERPOWERED", "Retired sample too small (n=%d). Cannot rule out survivorship.".formatted(retiredN));

            return formatter.format("SURVIVORSHIP_BIAS", activeRate, retiredRate, verdict,
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
        @ToolParam(description = "Cohort/vintage variable. " + EXPR_HINT) DenseExpressionDto cohortVariable,
        @ToolParam(description = "Outcome variable. " + EXPR_HINT) DenseExpressionDto outcome,
        @ToolParam(description = "Time period variable. " + EXPR_HINT) DenseExpressionDto timePeriod,
        @ToolParam(description = "Optional WHERE filter") @Nullable DenseExpressionDto filter,
        ToolContext toolContext
    ) {
        try {
            var ctx = RormToolContext.from(toolContext);
            requireCorrCompatible(ctx, rootName, cohortVariable, "cohortVariable", outcome, "outcome");

            var overallGradient = numVal(executor.executeSingle(
                    query(rootName, filter, regrSlope(outcome, cohortVariable, "overall_gradient")), ctx),
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
            var verdict = Verdict.rules()
                .supported(reversedPeriods == 0 && consistentPeriods > 0,
                    "True cohort effect. Gradient consistent across %d periods.".formatted(consistentPeriods))
                .when(reversedPeriods > totalValid / 2,
                    "TEMPORAL_CONFOUND", "Calendar effect. Gradient reverses in %d/%d periods. Overall (%.3f) confounded. Include time controls."
                        .formatted(reversedPeriods, totalValid, overallGradient))
                .orElse("CONDITIONAL", "Mixed: %d consistent, %d reversed periods."
                    .formatted(consistentPeriods, reversedPeriods));

            return formatter.format("TEMPORAL_CONFOUNDING", overallGradient,
                totalValid > 0 ? consistentPeriods / (double) totalValid : 0.0, verdict,
                Map.of("overall_gradient", overallGradient, "period_gradients", periodGradients,
                    "consistent_periods", consistentPeriods, "reversed_periods", reversedPeriods));
        } catch (Exception e) {
            log.error("Temporal confounding verification failed", e);
            return formatter.error("Verification failed: " + e.getMessage());
        }
    }

    @Tool(description = """
        SAMPLE SIZE ADEQUACY — Tests whether a stratified finding has adequate statistical power.

        Computes per-stratum: n, AVG(outcome), detectable effect size, confidence interval width.

        Verdict: ADEQUATE / PARTIALLY_ADEQUATE / UNDERPOWERED.""")
    public String verifySampleSizeAdequacy(
        @ToolParam(description = "Root entity name") String rootName,
        @ToolParam(description = "Stratification variable. " + EXPR_HINT) DenseExpressionDto stratumVariable,
        @ToolParam(description = "Outcome variable. " + EXPR_HINT) DenseExpressionDto outcome,
        @ToolParam(description = "Minimum meaningful effect size (delta)") double minimumMeaningfulEffect,
        @ToolParam(description = "Optional WHERE filter") @Nullable DenseExpressionDto filter,
        ToolContext toolContext
    ) {
        try {
            var ctx = RormToolContext.from(toolContext);
            requireCorrCompatible(ctx, rootName, outcome, "outcome");
            var results = executor.execute(groupedQuery(rootName, stratumVariable, filter,
                avg(outcome, "observed_effect"), stddevPop(outcome, "outcome_sd"), count("n")
            ), ctx);

            var stratumAdequacy = new ArrayList<Map<String, Object>>();
            var adequateStrata = 0;
            var underpoweredStrata = 0;
            for (var row : results) {
                var n = longVal(row, "n");
                var observedEffect = numVal(row, "observed_effect");
                var baseRate = Math.clamp(observedEffect, 0.01, 0.99);
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
            var verdict = Verdict.rules()
                .when(underpoweredStrata == 0,
                    "ADEQUATE", false, "All %d strata have sufficient power.".formatted(adequateStrata))
                .when(underpoweredStrata < totalStrata / 2,
                    "PARTIALLY_ADEQUATE", "%d/%d strata underpowered. Consider pooling weak strata."
                        .formatted(underpoweredStrata, totalStrata))
                .orElse("UNDERPOWERED", "%d/%d strata underpowered. Finding unreliable."
                    .formatted(underpoweredStrata, totalStrata));

            return formatter.format("SAMPLE_SIZE_ADEQUACY", minimumMeaningfulEffect,
                totalStrata > 0 ? adequateStrata / (double) totalStrata : 0.0, verdict,
                Map.of("stratum_adequacy", stratumAdequacy,
                    "adequate_strata", adequateStrata, "underpowered_strata", underpoweredStrata));
        } catch (Exception e) {
            log.error("Sample size adequacy verification failed", e);
            return formatter.error("Verification failed: " + e.getMessage());
        }
    }

    @Tool(description = """
        CONFOUNDER COMPLETENESS — Tests whether a DAG edge (treatment -> outcome) is missing confounders.

        For each candidate: computes CORR(candidate, treatment) and CORR(candidate, outcome).
        If any unlisted variable correlates with both, the DAG is incomplete.

        Verdict: COMPLETE / MISSING_CONFOUNDER.""")
    public String verifyConfounderCompleteness(
        @ToolParam(description = "Root entity name") String rootName,
        @ToolParam(description = "Treatment variable. " + EXPR_HINT) DenseExpressionDto treatment,
        @ToolParam(description = "Outcome variable. " + EXPR_HINT) DenseExpressionDto outcome,
        @ToolParam(description = "Candidate expressions NOT already in the confounder set") List<DenseExpressionDto> candidateVariables,
        @ToolParam(description = "Minimum correlation threshold to flag a missing confounder") double correlationThreshold,
        @ToolParam(description = "Optional WHERE filter") @Nullable DenseExpressionDto filter,
        ToolContext toolContext
    ) {
        try {
            var ctx = RormToolContext.from(toolContext);
            requireCorrCompatible(ctx, rootName, treatment, "treatment", outcome, "outcome");
            for (var c : candidateVariables) requireCorrCompatible(ctx, rootName, c, "candidateVariable");
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
                    && Math.abs(corrOutcome) >= correlationThreshold && n >= 100) {
                    var label = candidate.path() != null ? candidate.path() : candidate.toString();
                    missingConfounders.add(Map.of("variable", label,
                        "corr_with_treatment", corrTreatment, "corr_with_outcome", corrOutcome,
                        "bias_direction", (corrTreatment * corrOutcome > 0) ? "positive" : "negative", "n", n));
                }
            }

            var verdict = missingConfounders.isEmpty()
                ? new Verdict("COMPLETE", false, "No missing confounders detected among observed variables.")
                : new Verdict("MISSING_CONFOUNDER", true, formatStrongestConfounder(missingConfounders));

            return formatter.format("CONFOUNDER_COMPLETENESS", 0.0, missingConfounders.size(), verdict,
                Map.of("missing_confounders", missingConfounders));
        } catch (Exception e) {
            log.error("Confounder completeness verification failed", e);
            return formatter.error("Verification failed: " + e.getMessage());
        }
    }

    @Tool(description = """
        DAG COMPLETENESS — Tests whether the causal DAG is missing edges by checking pairwise
        correlations among variables in different roles.
        
        Three checks:
        (a) CONTROL → TREATMENT: corr(control, treatment). If substantial but no DAG edge,
            the control may be a mediator or collider.
        (b) EXCLUDED → INCLUDED: corr(excluded, treatment) and corr(excluded, outcome).
            If both substantial, the excluded variable may be a confounder biasing the estimate.
        (c) CROSS-HYPOTHESIS: If variable X is treatment in one hypothesis but control in another,
            check corr(X, otherTreatment). Undocumented association means biased estimates.
        
        Verdict: DAG_COMPLETE / MISSING_EDGE.""")
    public String verifyDAGCompleteness(
        @ToolParam(description = "Root entity name") String rootName,
        @ToolParam(description = "Treatment variable (the hypothesized cause). " + EXPR_HINT) DenseExpressionDto treatment,
        @ToolParam(description = "Outcome variable. " + EXPR_HINT) DenseExpressionDto outcome,
        @ToolParam(description = "Saturated control variables (controlFeatures from SS runs). " + EXPR_HINT) List<DenseExpressionDto> controls,
        @ToolParam(description = "Excluded variables (BELOW_DETECTION, absorbed by proxy). " + EXPR_HINT) List<DenseExpressionDto> excluded,
        @ToolParam(description = "Treatments from other hypotheses that share variables with this one. " + EXPR_HINT) List<DenseExpressionDto> crossHypothesisTreatments,
        @ToolParam(description = "Minimum correlation threshold to flag a missing edge") double correlationThreshold,
        @ToolParam(description = "Optional WHERE filter") @Nullable DenseExpressionDto filter,
        ToolContext toolContext
    ) {
        try {
            var ctx = RormToolContext.from(toolContext);
            requireCorrCompatible(ctx, rootName, treatment, "treatment", outcome, "outcome");

            var missingEdges = new ArrayList<Map<String, Object>>();

            checkControlTreatmentEdges(rootName, treatment, controls, correlationThreshold, filter, ctx, missingEdges);
            checkExcludedVariableEdges(rootName, treatment, outcome, excluded, correlationThreshold, filter, ctx, missingEdges);
            checkCrossHypothesisEdges(rootName, treatment, crossHypothesisTreatments, correlationThreshold, filter, ctx, missingEdges);

            var verdict = missingEdges.isEmpty()
                ? new Verdict("DAG_COMPLETE", false,
                "No undocumented pairwise associations above threshold %.2f.".formatted(correlationThreshold))
                : new Verdict("MISSING_EDGE", true,
                "Found %d missing edge(s). Strongest: %s".formatted(missingEdges.size(),
                    formatStrongestEdge(missingEdges)));

            return formatter.format("DAG_COMPLETENESS", 0.0, missingEdges.size(), verdict,
                Map.of("missing_edges", missingEdges, "threshold", correlationThreshold));
        } catch (Exception e) {
            log.error("DAG completeness verification failed", e);
            return formatter.error("Verification failed: " + e.getMessage());
        }
    }

    private void checkControlTreatmentEdges(
        String rootName, DenseExpressionDto treatment, List<DenseExpressionDto> controls,
        double threshold, @Nullable DenseExpressionDto filter, RormToolContext ctx,
        List<Map<String, Object>> missingEdges
    ) {
        for (var control : controls) {
            requireCorrCompatible(ctx, rootName, control, "control");
            var row = executor.executeSingle(query(rootName, filter,
                corr(control, treatment, "corr_value"), count("n")
            ), ctx);
            var corrValue = numVal(row, "corr_value");
            var n = longVal(row, "n");
            if (Math.abs(corrValue) >= threshold && n >= 100) {
                missingEdges.add(Map.of(
                    "check", "CONTROL_TREATMENT",
                    "variable", labelOf(control),
                    "correlation", corrValue,
                    "n", n,
                    "implication", Math.abs(corrValue) > 0.5
                        ? "Control may be a mediator (blocking real signal) or collider (opening spurious path)"
                        : "Weak undocumented association between control and treatment"));
            }
        }
    }

    private void checkExcludedVariableEdges(
        String rootName, DenseExpressionDto treatment, DenseExpressionDto outcome,
        List<DenseExpressionDto> excluded, double threshold,
        @Nullable DenseExpressionDto filter, RormToolContext ctx,
        List<Map<String, Object>> missingEdges
    ) {
        for (var excl : excluded) {
            requireCorrCompatible(ctx, rootName, excl, "excluded");
            var row = executor.executeSingle(query(rootName, filter,
                corr(excl, treatment, "corr_treatment"),
                corr(excl, outcome, "corr_outcome"),
                count("n")
            ), ctx);
            var corrTreat = numVal(row, "corr_treatment");
            var corrOut = numVal(row, "corr_outcome");
            var n = longVal(row, "n");
            if (Math.abs(corrTreat) >= threshold && Math.abs(corrOut) >= threshold && n >= 100) {
                missingEdges.add(Map.of(
                    "check", "EXCLUDED_CONFOUNDER",
                    "variable", labelOf(excl),
                    "corr_with_treatment", corrTreat,
                    "corr_with_outcome", corrOut,
                    "n", n,
                    "bias_direction", (corrTreat * corrOut > 0) ? "positive" : "negative",
                    "implication", "Excluded variable correlates with both treatment and outcome — potential confounder"));
            }
        }
    }

    private void checkCrossHypothesisEdges(
        String rootName, DenseExpressionDto treatment, List<DenseExpressionDto> otherTreatments,
        double threshold, @Nullable DenseExpressionDto filter, RormToolContext ctx,
        List<Map<String, Object>> missingEdges
    ) {
        for (var otherTreat : otherTreatments) {
            requireCorrCompatible(ctx, rootName, otherTreat, "crossHypothesisTreatment");
            var row = executor.executeSingle(query(rootName, filter,
                corr(treatment, otherTreat, "corr_value"), count("n")
            ), ctx);
            var corrValue = numVal(row, "corr_value");
            var n = longVal(row, "n");
            if (Math.abs(corrValue) >= threshold && n >= 100) {
                missingEdges.add(Map.of(
                    "check", "CROSS_HYPOTHESIS",
                    "variable", labelOf(otherTreat),
                    "correlation", corrValue,
                    "n", n,
                    "implication", "Undocumented association between treatments of different hypotheses — " +
                                   "estimates may be biased by unmodeled path"));
            }
        }
    }

    private static String formatStrongestEdge(List<Map<String, Object>> edges) {
        var strongest = edges.stream()
            .max(Comparator.comparingDouble(e -> {
                if (e.containsKey("correlation")) {
                    return Math.abs((double) e.get("correlation"));
                }
                return Math.abs((double) e.get("corr_with_treatment")) * Math.abs((double) e.get("corr_with_outcome"));
            }))
            .orElseThrow();
        return "%s (%s) — %s".formatted(strongest.get("variable"), strongest.get("check"), strongest.get("implication"));
    }

    private static String labelOf(DenseExpressionDto expr) {
        return expr.path() != null ? expr.path() : expr.toString();
    }

    private static String formatStrongestConfounder(List<Map<String, Object>> confounders) {
        var strongest = confounders.stream()
            .max(Comparator.comparingDouble(m ->
                Math.abs((double) m.get("corr_with_treatment")) * Math.abs((double) m.get("corr_with_outcome"))))
            .orElseThrow();
        return "Missing confounder(s). Strongest: %s (r_treat=%.3f, r_out=%.3f, bias=%s). Total: %d".formatted(
            strongest.get("variable"), (double) strongest.get("corr_with_treatment"),
            (double) strongest.get("corr_with_outcome"), strongest.get("bias_direction"),
            confounders.size());
    }
}
