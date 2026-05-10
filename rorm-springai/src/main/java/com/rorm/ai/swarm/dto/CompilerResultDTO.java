package com.rorm.ai.swarm.dto;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/**
 * Final structured output of the executor-compiler step.
 * <p>
 * The compiler iterates internally by calling {@code executePipeline}
 * (and optionally {@code reexecuteCausalPipeline} for cheap variant
 * exploration). Every run is captured server-side under a stable
 * {@code runId}; downstream agents drill into specs and results via
 * {@code queryRunSpec(runId, jsonPath)} and
 * {@code queryRunResult(runId, jsonPath)}. This DTO carries ONLY the
 * compiler's narrative briefs over those runs, never the full spec or
 * result payload.
 */
@JsonClassDescription("""
    Final compiler output. The compiler ran one or more pipeline executions
    via the executePipeline tool; the runs are persisted under their runIds
    and reachable via queryRunSpec / queryRunResult. This DTO is the
    compiler's narrative over those runs: per-run briefs (what changed
    from the previous attempt, what surfaced in the result), the runId(s)
    it endorses as the canonical answer, and a short convergence narrative.
    Downstream agents read this DTO and drill into specific run fields by
    JSONPath rather than receiving full JSON dumps.""")
public record CompilerResultDTO(

    @JsonProperty(required = true)
    @JsonPropertyDescription("""
        Per-run briefs the compiler emits over its execution attempts.
        One entry per run worth narrating; throwaway probes the compiler
        considered uninformative may be omitted. Every runId listed must
        match a JobEvent.jobId from a run the compiler actually executed
        in this step — never invent ids and never reference runs from
        prior steps.""")
    List<RunBrief> runBriefs,

    @JsonProperty(required = true)
    @JsonPropertyDescription("""
        Run id(s) the compiler considers definitive — the canonical answer
        the sceptic and downstream phases should treat as the result of
        this hypothesis's compilation. Usually one entry; multiple when
        the compiler endorses convergent evidence across spec variants
        (e.g. continuous + binary-threshold of the same treatment).
        Empty list signals the compiler explicitly produced no usable
        run; downstream will treat the hypothesis as compile-failed.""")
    List<String> selectedRunIds,

    @JsonProperty(required = true)
    @JsonPropertyDescription("""
        2–5 sentence narrative explaining the design choices the compiler
        made and why it converged on the selected run(s). NOT a spec
        re-statement — focus on the meaningful tradeoffs (which W set,
        which estimation variant, which scope) and what the runs revealed.
        The sceptic and supervisor read this as a starting point and
        drill into specific fields via queryRunSpec / queryRunResult.""")
    String convergenceSummary
) {}
