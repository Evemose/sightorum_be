package com.rorm.ai.swarm;

import com.rorm.ai.swarm.dto.*;
import com.rorm.ml.dto.PipelineSpecRequest;
import com.rorm.ml.stream.JobEvent;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Complete result of a durable swarm run. Each agent output is structured
 * via a {@link com.rorm.ai.swarm.agents.SecondarySwarmAgent} into a typed DTO —
 * by default the summarizing impl, with the compiler step reading the
 * pipeline spec straight from its tool-populated holder.
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
     */
    public record HypothesisResult(
        String hypothesisId,
        String hypothesisSpec,
        PipelineSpecRequest compilerOutput,
        @Nullable JobEvent pipelineResult,
        @Nullable CompilerCorrectionDTO compilerScepticReview,
        @Nullable ForensicDiagnosisDTO diagnosis,
        @Nullable StandoffArgumentDTO advocate,
        @Nullable StandoffArgumentDTO prosecutor,
        @Nullable SupervisorVerdictDTO supervisorVerdict
    ) {
    }
}
