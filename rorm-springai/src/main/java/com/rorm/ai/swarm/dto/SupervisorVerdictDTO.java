package com.rorm.ai.swarm.dto;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.jspecify.annotations.Nullable;

@JsonClassDescription("""
    Routing verdict from the supervisor for one iteration of the
    generator-compiler-sceptic trio. Decides whether the loop terminates
    for this hypothesis or which step the next iteration starts at.""")
public record SupervisorVerdictDTO(

    @JsonPropertyDescription("""
        The routing decision. Exactly one of:
        PASS_THROUGH — terminate the loop; accept the latest compiler runs
            and sceptic review as final.
        LOOP_TO_COMPILER — re-run the compiler in its existing chat with
            compilerFocus as additional guidance. The compiler may execute
            additional pipelines via the executePipeline tool. Cheap-ish:
            no re-generation, but pipeline executions cost what they cost.
        LOOP_TO_GENERATOR — re-run the generator rebuttal (which then
            forces a re-compile and re-sceptic) with refinementRequest as
            additional guidance. Full new round.""")
    @JsonProperty(required = true)
    Decision decision,

    @JsonPropertyDescription("""
        Reason classification when decision is PASS_THROUGH; null when
        decision is a LOOP. HYPOTHESIS_DEAD — the trio demonstrated a
        structural failure no further iteration can repair.
        MARGINAL_RETURNS — successive iterations produce diminishing
        deltas; one more pass would not move the estimate or its
        uncertainty meaningfully. WELL_FORMED — the hypothesis is
        well-specified, gates green, sceptic clean.""")
    @Nullable PassThroughReason passReason,

    @JsonPropertyDescription("""
        One-sentence explanation pinning the chosen decision to a
        specific cited cause in the inputs (a numeric delta, a named
        sceptic finding, a named gate flag).""")
    @JsonProperty(required = true)
    String explanation,

    @JsonPropertyDescription("""
        Concrete, named instruction for the compiler when decision is
        LOOP_TO_COMPILER. Null otherwise. Names a specific defect in the
        compiler's spec or execution choice — e.g. a missing confounder,
        a binary cutoff that needs revision, a refutation gate that
        should be enabled. Never abstract guidance.""")
    @Nullable String compilerFocus,

    @JsonPropertyDescription("""
        Concrete, named instruction for the generator rebuttal when
        decision is LOOP_TO_GENERATOR. Null otherwise. Names a specific
        defect of the hypothesis spec — never abstract guidance.""")
    @Nullable String refinementRequest,

    @JsonPropertyDescription("""
        Compact running notes (3-8 sentences) for the supervisor's own
        future iterations on this hypothesis. Captures what changed in
        this iteration vs the previous one, the decision and its driver,
        and what to re-evaluate if another iteration occurs. These notes
        feed into the next iteration's PRIOR_NOTES input.""")
    @JsonProperty(required = true)
    String supervisorNotes
) {

    public enum Decision {PASS_THROUGH, LOOP_TO_COMPILER, LOOP_TO_GENERATOR}

    public enum PassThroughReason {HYPOTHESIS_DEAD, MARGINAL_RETURNS, WELL_FORMED}
}
