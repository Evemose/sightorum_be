package com.rorm.ai.swarm;

import com.rorm.ai.swarm.dto.*;
import com.rorm.ml.dto.RunRecord;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Complete result of a durable swarm run. Each agent output is structured
 * via a {@link com.rorm.ai.swarm.agents.SecondarySwarmAgent} into a typed DTO —
 * by default the summarizing impl, with the compiler step running its
 * own iterative {@code executePipeline} loop and emitting a
 * {@link CompilerResultDTO} that references the runs it explored.
 */
public record SwarmResult(
    ScoutAnalysisDTO scoutOutput,
    DomainResearchDTO domainResearch,
    List<AnchorResult> anchorResults,
    @Nullable JudgeVerdictDTO judgeVerdict
) {

    /**
     * Result for a single anchor perspective (one generator).
     */
    public record AnchorResult(
        String anchor,
        String generatorChatId,
        HypothesisGenerationDTO generatorOutput,
        ScepticReviewDTO scepticOutput,
        HypothesisGenerationDTO revisedOutput,
        List<HypothesisResult> hypothesisResults
    ) {
    }

    /**
     * Result for a single hypothesis from an anchor's generator.
     * <p>
     * {@code compilerRuns} and {@code scepticRuns} are filtered views over
     * the hypothesis's accumulated tool-call list — split by
     * {@link RunRecord#producerEventId()}{@code .kind()}. Each
     * {@code RunRecord} carries the originating agent's full
     * {@link com.rorm.ai.swarm.EventId} so consumers can disambiguate
     * across iterations and hypotheses by token.
     *
     * @param compilerRaw    the compiler's free-text agent output
     * @param compilerOutput typed projection of the compiler's output
     * @param compilerRuns   runs produced under {@code "compiler"} kind
     *                       (every iteration of every compiler step in
     *                       this hypothesis chain, in submission order)
     * @param scepticRaw     the sceptic's free-text guardrail review
     * @param scepticRuns    runs produced under {@code "compiler-sceptic"}
     *                       kind during the sceptic's guardrail review
     */
    public record HypothesisResult(
        String hypothesisId,
        String hypothesisSpec,
        String compilerRaw,
        CompilerResultDTO compilerOutput,
        List<RunRecord> compilerRuns,
        String scepticRaw,
        List<RunRecord> scepticRuns,
        @Nullable CompilerCorrectionDTO compilerScepticReview,
        @Nullable ForensicDiagnosisDTO diagnosis,
        @Nullable StandoffArgumentDTO advocate,
        @Nullable StandoffArgumentDTO prosecutor,
        @Nullable SupervisorVerdictDTO supervisorVerdict,
        @Nullable EventId advocateId,
        @Nullable EventId prosecutorId,
        @Nullable EventId diagnosisId
    ) {
    }
}
