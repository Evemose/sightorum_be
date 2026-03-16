package com.rorm.ai.swarm;

import com.rorm.ai.swarm.SwarmEvent.EndEvent;
import com.rorm.ai.swarm.SwarmEvent.StartEvent;
import com.rorm.ai.swarm.dto.*;
import com.rorm.ml.stream.JobEvent;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.UUID;

public sealed interface SwarmEvent permits StartEvent, EndEvent {

    sealed interface StartEvent extends SwarmEvent permits
        AnalysisNegotiationStarted,
        AnalysisVersionCreationStarted,
        AnalysisVersionCritiqueStarted,
        BranchExecutionStarted,
        PlanNegotiationStarted,
        PlanVersionCreationStarted,
        PlanVersionCritiqueStarted,
        ScoutStarted,
        StepExecutionStarted,
        StepJobAwaitStarted {
        Flux<String> tokenStream();
    }

    sealed interface EndEvent<T> extends SwarmEvent permits
        AnalysisNegotiationFinished,
        AnalysisVersionCreationFinished,
        AnalysisVersionCritiqueFinished,
        BranchExecutionFinished,
        PlanNegotiationFinished,
        PlanVersionCreationFinished,
        PlanVersionCritiqueFinished,
        ScoutFinished,
        StepExecutionFinished,
        StepJobCompleted {
        T findings();

        String rawResponse();
    }

    record ScoutStarted(String scoutId, Flux<String> tokenStream) implements StartEvent {}

    record ScoutFinished(String scoutId, ScoutOverviewDTO findings,
                         String rawResponse) implements EndEvent<ScoutOverviewDTO> {}

    record PlanNegotiationStarted(String planningId, Flux<String> tokenStream) implements StartEvent {}

    record PlanNegotiationFinished(
        String planningId,
        ResearchPlanDTO findings,
        String rawResponse,
        NegotiationFinishReason finishReason
    ) implements EndEvent<ResearchPlanDTO> {}

    record BranchExecutionStarted(String branchId, Flux<String> tokenStream) implements StartEvent {}

    record PlanVersionCreationStarted(String planningId, int versionNumber,
                                      Flux<String> tokenStream) implements StartEvent {}

    record PlanVersionCreationFinished(
        String planningId,
        int versionNumber,
        ResearchPlanDTO findings,
        String rawResponse
    ) implements EndEvent<ResearchPlanDTO> {}

    record PlanVersionCritiqueStarted(String planningId, int versionNumber,
                                      Flux<String> tokenStream) implements StartEvent {}

    record PlanVersionCritiqueFinished(
        String planningId,
        int versionNumber,
        PlanCritiqueDTO findings,
        String rawResponse
    ) implements EndEvent<PlanCritiqueDTO> {}

    record BranchExecutionFinished(
        String branchId,
        BranchExecutionResultDTO findings,
        String rawResponse
    ) implements EndEvent<BranchExecutionResultDTO> {}

    record StepExecutionStarted(
        String branchId,
        String stepId,
        String previousStepId,
        List<StepRef> dependencies,
        Flux<String> tokenStream
    ) implements StartEvent {}

    record StepExecutionFinished(
        String branchId,
        String stepId,
        String previousStepId,
        List<StepRef> dependencies,
        StepExecutionResultDTO findings,
        String rawResponse
    ) implements EndEvent<StepExecutionResultDTO> {}

    record AnalysisNegotiationStarted(String analysisId, Flux<String> tokenStream) implements StartEvent {}

    record AnalysisNegotiationFinished(
        String analysisId,
        AnalysisResultDTO findings,
        String rawResponse,
        NegotiationFinishReason reason
    ) implements EndEvent<AnalysisResultDTO> {}

    record AnalysisVersionCreationStarted(String analysisId, int versionNumber,
                                          Flux<String> tokenStream) implements StartEvent {}

    record AnalysisVersionCreationFinished(
        String analysisId,
        int versionNumber,
        AnalysisResultDTO findings,
        String rawResponse
    ) implements EndEvent<AnalysisResultDTO> {}

    record AnalysisVersionCritiqueStarted(String analysisId, int versionNumber,
                                          Flux<String> tokenStream) implements StartEvent {}

    record AnalysisVersionCritiqueFinished(
        String analysisId,
        int versionNumber,
        ConclusionCritiqueDTO findings,
        String rawResponse
    ) implements EndEvent<ConclusionCritiqueDTO> {}

    record StepJobAwaitStarted(
        String branchId,
        String stepId,
        UUID jobId
    ) implements StartEvent {
        public Flux<String> tokenStream() {
            return Flux.empty();
        }
    }

    record StepJobCompleted(
        String branchId,
        String stepId,
        UUID jobId,
        JobEvent result
    ) implements EndEvent<JobEvent> {
        public String rawResponse() {
            return "";
        }

        public JobEvent findings() {
            return result;
        }
    }

}
