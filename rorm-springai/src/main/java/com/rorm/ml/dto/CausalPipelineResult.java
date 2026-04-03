package com.rorm.ml.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;

@JsonNaming(SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record CausalPipelineResult(
    String hypothesisId,
    int rowCount,
    @Nullable RowCountMismatch rowCountMismatch,
    Steps steps,
    @Nullable Double finalEffect,
    @Nullable List<Double> finalCi,
    @Nullable String finalCiNote,
    boolean ciCrossesZero,
    @Nullable List<DiscrepancyEntry> discrepancyLog,
    @Nullable String estimationFailure
) {

    @JsonNaming(SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RowCountMismatch(int expected, int actual) {}

    @JsonNaming(SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DiscrepancyEntry(
        String field,
        @Nullable String generatorValue,
        @Nullable String compilerValue,
        @Nullable String resolution
    ) {}

    // -- Steps container --

    @JsonNaming(SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Steps(
        @Nullable DsepResult dsep,
        @Nullable IdentificationResult identification,
        @Nullable PositivityReport positivity,
        @Nullable Map<String, EstimationVariantResult> estimation,
        @Nullable GatesResult gates,
        @Nullable List<MediationEntry> mediation,
        @Nullable List<GrfConfigResult> grf,
        @Nullable Map<String, RefutationEntry> refutations,
        @Nullable List<UnmeasuredConfoundingEntry> unmeasuredConfounding,
        @Nullable SensitivityResult sensitivity,
        @Nullable List<StructuralBreakResult> structuralBreaks,
        @Nullable ResidualDiagnosticsResult residualDiagnostics,
        @Nullable RangeChecksResult rangeChecks,
        @Nullable ExternalizationResult externalization,
        @Nullable NullDiagnosticsResult nullDiagnostics
    ) {}

    // -- D-sep refinement --

    @JsonNaming(SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DsepResult(
        int broadEdgeCount,
        int refinedEdgeCount,
        int violationCount,
        int confirmedCount,
        List<List<String>> fallbackEdges,
        List<List<String>> refinedEdges,
        List<DsepViolation> violations
    ) {}

    @JsonNaming(SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DsepViolation(
        String nodeA,
        String nodeB,
        double correlation,
        double pValue
    ) {}

    // -- Identification --

    @JsonNaming(SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record IdentificationResult(
        List<String> adjustmentSet,
        List<String> mediatorsExcluded
    ) {}

    // -- Positivity --

    @JsonNaming(SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PositivityReport(
        List<PositivityLevel> attemptedLevels,
        @Nullable String finalLevel,
        int originalN,
        int survivingN,
        @Nullable List<SparseCell> trimmedCells,
        @Nullable String failure
    ) {}

    @JsonNaming(SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PositivityLevel(
        String level,
        int nCells,
        int sparseCellsCount,
        @Nullable List<SparseCell> sparseCells,
        double coveragePct,
        String verdict
    ) {}

    @JsonNaming(SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SparseCell(
        String treatment,
        String confounder,
        int count
    ) {}

    // -- Estimation --

    @JsonNaming(SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EstimationVariantResult(
        String variantId,
        @Nullable Double effect,
        @Nullable List<Double> ci,
        @Nullable String error,
        @Nullable String treatmentColumn,
        @Nullable List<String> wColumns,
        @Nullable Integer nObs,
        @Nullable Boolean discrete,
        @Nullable Map<String, Double> categoryEffects
    ) {}

    // -- Quality gates --

    @JsonNaming(SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GatesResult(
        NuisanceR2 nuisanceR2,
        @Nullable SanityCheck sanity
    ) {}

    @JsonNaming(SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record NuisanceR2(
        double outcomeR2,
        double treatmentR2,
        String outcomeStatus,
        String treatmentStatus
    ) {}

    @JsonNaming(SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SanityCheck(
        boolean directionOk,
        double effectMagnitude,
        double flagMagnitude,
        String status,
        @Nullable String warning
    ) {}

    // -- Mediation --

    @JsonNaming(SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MediationEntry(
        String mediator,
        @Nullable String pathway,
        @Nullable Double directEffect,
        @Nullable Double mediatedEffect,
        @Nullable Double fraction,
        @Nullable String error
    ) {}

    // -- GRF heterogeneity --

    @JsonNaming(SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GrfConfigResult(
        String configId,
        @Nullable Map<String, GrfSlice> slices,
        @Nullable Map<String, Double> featureImportances,
        @Nullable Double meanCate,
        @Nullable Double stdCate,
        @Nullable String error
    ) {}

    @JsonNaming(SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GrfSlice(double meanCate, double stdCate, int n) {}

    // -- Refutations --

    @JsonNaming(SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RefutationEntry(
        @Nullable Double newEffect,
        @Nullable Double ratio,
        @Nullable Boolean flag,
        @Nullable Double shiftPct,
        @Nullable String temporalColumn,
        @Nullable Double originalEffect,
        @Nullable List<TemporalProbe> probes,
        @Nullable Double worstRatio,
        @Nullable String error
    ) {}

    @JsonNaming(SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TemporalProbe(
        String label,
        int lag,
        @Nullable Integer nObs,
        @Nullable Double effect,
        @Nullable Double ratio,
        @Nullable String error
    ) {}

    // -- Unmeasured confounding --

    @JsonNaming(SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UnmeasuredConfoundingEntry(
        String variantId,
        @Nullable String method,
        @Nullable Double rr,
        @Nullable Double eValuePoint,
        @Nullable Double eValueCi,
        @Nullable String nullHypothesis,
        @Nullable String notes,
        @Nullable String error
    ) {}

    // -- Sensitivity --

    @JsonNaming(SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SensitivityResult(
        List<ConfounderDrop> confounderDrops,
        List<ConfounderAdd> confounderAdds,
        List<ThresholdVariant> thresholdVariants,
        List<ModelVariant> modelVariants
    ) {}

    @JsonNaming(SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ConfounderDrop(
        String column,
        @Nullable Double effect,
        @Nullable Double deviationPct,
        double thresholdPct,
        boolean flag
    ) {}

    @JsonNaming(SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ConfounderAdd(
        String column,
        @Nullable String reasoning,
        @Nullable Double effect,
        @Nullable Double deviationPct,
        @Nullable String error
    ) {}

    @JsonNaming(SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ThresholdVariant(
        double threshold,
        @Nullable Double effect,
        int nTreated,
        int nControl,
        @Nullable Integer expectedNTreated,
        @Nullable Integer expectedNControl
    ) {}

    @JsonNaming(SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ModelVariant(
        String primaryVariantId,
        String alternativeModelType,
        @Nullable Double primaryEffect
    ) {}

    // -- Structural breaks --

    @JsonNaming(SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StructuralBreakResult(
        String id,
        @Nullable Integer totalBreaks,
        @Nullable Integer directionMatches,
        @Nullable Integer ciMatches,
        @Nullable Integer tier,
        @Nullable List<BreakMatch> matches,
        @Nullable String error
    ) {}

    @JsonNaming(SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BreakMatch(
        String entity,
        String breakDate,
        double actualChange,
        double treatmentDelta,
        double predictedChange,
        boolean directionMatch,
        boolean withinCi
    ) {}

    // -- Residual diagnostics --

    @JsonNaming(SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ResidualDiagnosticsResult(
        List<AutocorrelationCheck> autocorrelation,
        List<FieldCorrelation> fieldCorrelations,
        List<ResidualCorrection> corrections,
        @Nullable Double correctedEffect,
        List<MetadataCorrelation> metadataCorrelations
    ) {}

    @JsonNaming(SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AutocorrelationCheck(
        String temporalColumn,
        double durbinWatson,
        boolean flagged
    ) {}

    @JsonNaming(SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FieldCorrelation(
        String column,
        double correlation,
        boolean isMetadata
    ) {}

    @JsonNaming(SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ResidualCorrection(
        String field,
        boolean isMetadata,
        double original,
        double corrected,
        double deltaPct
    ) {}

    @JsonNaming(SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MetadataCorrelation(
        String column,
        double correlation,
        double threshold,
        boolean flagged,
        String alertType
    ) {}

    // -- Range checks --

    @JsonNaming(SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RangeChecksResult(
        VifResult vif,
        double treatmentCv,
        List<OverlapEntry> overlap,
        List<VarianceEntry> variance
    ) {}

    @JsonNaming(SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record VifResult(
        Map<String, @Nullable Double> values,
        double threshold,
        List<String> flagged
    ) {}

    @JsonNaming(SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OverlapEntry(
        String variantId,
        @Nullable Double overlap,
        @Nullable Double threshold,
        @Nullable Boolean flagged,
        @Nullable String responseStrategy,
        @Nullable String error
    ) {}

    @JsonNaming(SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record VarianceEntry(
        String column,
        double std,
        @Nullable Double cv,
        @Nullable String structuralNote
    ) {}

    // -- Externalization --

    @JsonNaming(SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExternalizationResult(
        List<DomainRankingResult> domainRankings,
        List<AllocationBiasEntry> allocationBias
    ) {}

    @JsonNaming(SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DomainRankingResult(
        String ordering,
        String source,
        String scope,
        double expectedConcordance,
        @Nullable String sourceType,
        @Nullable Map<String, Double> effectsUsed,
        @Nullable Integer totalPairs,
        @Nullable Integer crossTierPairs,
        @Nullable Integer withinTierPairs,
        @Nullable Integer matchedPairs,
        @Nullable Double concordance,
        @Nullable List<String> unmatchedElements,
        @Nullable Map<String, NodeMismatch> nodeMismatches,
        @Nullable OrderingDetails details,
        @Nullable Boolean pass,
        @Nullable String error
    ) {}

    @JsonNaming(SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OrderingDetails(
        List<PairDetail> crossTier,
        List<PairDetail> withinTier
    ) {}

    @JsonNaming(SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PairDetail(
        String left,
        String right,
        String relation,
        double leftValue,
        double rightValue,
        @Nullable Integer leftN,
        @Nullable Integer rightN,
        boolean satisfied
    ) {}

    @JsonNaming(SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record NodeMismatch(int leftViolations, int rightViolations, int appearances) {}

    @JsonNaming(SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AllocationBiasEntry(
        String treatmentColumn,
        String groupingColumn,
        @Nullable Double maxDeviation,
        @Nullable Boolean flagged,
        @Nullable String error
    ) {}

    // -- Null-finding diagnostics --

    @JsonNaming(SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record NullDiagnosticsResult(
        AbsorptionCurve absorptionCurve,
        PowerAnalysis powerAnalysis,
        List<SubpopulationEdge> subpopulationEdges
    ) {}

    @JsonNaming(SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AbsorptionCurve(
        List<AbsorptionStep> curve,
        List<String> confounderOrder
    ) {}

    @JsonNaming(SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AbsorptionStep(
        int step,
        @Nullable String added,
        List<String> confounders,
        @Nullable Double effect,
        @Nullable Double absorbed
    ) {}

    @JsonNaming(SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PowerAnalysis(
        int nObservations,
        int nTreatmentLevels,
        double observedSe,
        double mde80Power,
        @Nullable Double observedEffect,
        @Nullable Boolean effectBelowMde,
        @Nullable Integer nNeededForObservedEffect
    ) {}

    @JsonNaming(SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SubpopulationEdge(
        String variantId,
        double effect,
        List<Double> ci,
        boolean crossesZero,
        double nearestBoundToZero,
        double zNearest,
        double se,
        String classification
    ) {}
}
