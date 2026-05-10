package com.rorm.ai.swarm.dto;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Per-run narrative an agent (compiler or sceptic) emits for one of
 * its pipeline execution attempts. The {@code runId} references a
 * server-stored run reachable via {@code queryRunSpec(runId, jsonPath)}
 * and {@code queryRunResult(runId, jsonPath)}. The brief describes
 * what the agent changed in this run (versus the previous attempt or
 * the baseline) and what surfaced in its result that drove the
 * verdict — concrete pointers, never a re-statement of the full spec
 * or result JSON.
 */
@JsonClassDescription("""
    One run's narrative brief: which spec variant it represents, what
    changed from the prior attempt, what surfaced in the result, and
    the agent's verdict. Downstream consumers use queryRunSpec /
    queryRunResult against the runId to drill into specific fields
    rather than receiving full JSON dumps.""")
public record RunBrief(

    @JsonProperty(required = true)
    @JsonPropertyDescription("""
        Run id; must equal the JobEvent.jobId of one of the agent's
        execution attempts in this step. Downstream agents pass this
        to queryRunSpec / queryRunResult to inspect spec or result
        fields.""")
    String runId,

    @JsonProperty(required = true)
    @JsonPropertyDescription("""
        One-sentence label of what spec variant this run represents
        (e.g. "continuous treatment, baseline W set",
        "binary 70% threshold dropping vendorTier",
        "DAG seed with cohort-by-vehicle interaction edge",
        "ablation: drop vendorTier from prior accepted run").""")
    String variantLabel,

    @JsonProperty(required = true)
    @JsonPropertyDescription("""
        What CHANGED in this run's spec relative to the prior attempt
        (or the baseline if this is the first run). 1–2 sentences
        naming the specific spec field(s) altered and why
        (e.g. "Dropped vendorTier from adjustment_set after pattern-3
        bundling check showed within-treatment STDDEV=0.02"). Empty
        string for the very first run when no prior attempt exists.""")
    String specDelta,

    @JsonProperty(required = true)
    @JsonPropertyDescription("""
        What SURFACED in the run's result that drove the agent's
        assessment: a specific gate verdict, refutation tier, ATE
        magnitude/sign, CI behavior, or diagnostic marker. Cite
        concrete numbers and field names so downstream consumers can
        verify via queryRunResult — but do NOT reproduce the full
        metrics blob here.""")
    String findings,

    @JsonProperty(required = true)
    @JsonPropertyDescription("""
        ACCEPTED — a contender for the agent's selected run set; the
        agent considers this run informative and methodologically
        sound. REJECTED — the agent explicitly rejects this run
        (findings must explain why). DIAGNOSTIC — a probe used to
        inform the design choice but not itself a candidate answer
        (e.g. an ablation re-execution).""")
    Verdict verdict
) {

    public enum Verdict {ACCEPTED, REJECTED, DIAGNOSTIC}
}
