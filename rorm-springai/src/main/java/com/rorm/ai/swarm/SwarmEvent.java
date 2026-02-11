package com.rorm.ai.swarm;

import com.rorm.ai.swarm.SwarmEvent.EndEvent;
import com.rorm.ai.swarm.SwarmEvent.StartEvent;
import com.rorm.ai.swarm.dto.*;
import reactor.core.publisher.Flux;

public sealed interface SwarmEvent permits
    StartEvent,
    EndEvent {

    sealed interface StartEvent extends SwarmEvent permits
        AnalysisNegotiationStarted,
        AnalysisVersionCreationStarted,
        AnalysisVersionCritiqueStarted,
        BranchExecutionStarted,
        PlanNegotiationStarted,
        PlanVersionCreationStarted,
        PlanVersionCritiqueStarted,
        ScoutStarted,
        StepExecutionStarted {
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
        StepExecutionFinished {
        T findings();

        String rawResponse();
    }

    record ScoutStarted(Flux<String> tokenStream) implements StartEvent {}

    record ScoutFinished(ScoutOverviewDTO findings, String rawResponse) implements EndEvent<ScoutOverviewDTO> {}

    record PlanNegotiationStarted(Flux<String> tokenStream) implements StartEvent {}

    record PlanNegotiationFinished(
        ResearchPlanDTO findings,
        String rawResponse,
        NegotiationFinishReason finishReason
    ) implements EndEvent<ResearchPlanDTO> {}

    record BranchExecutionStarted(Flux<String> tokenStream) implements StartEvent {}

    record PlanVersionCreationStarted(Flux<String> tokenStream) implements StartEvent {}

    record PlanVersionCreationFinished(
        ResearchPlanDTO findings,
        String rawResponse
    ) implements EndEvent<ResearchPlanDTO> {}

    record PlanVersionCritiqueStarted(Flux<String> tokenStream) implements StartEvent {}

    record PlanVersionCritiqueFinished(
        PlanCritiqueDTO findings,
        String rawResponse
    ) implements EndEvent<PlanCritiqueDTO> {}

    record BranchExecutionFinished(
        BranchExecutionResultDTO findings,
        String rawResponse
    ) implements EndEvent<BranchExecutionResultDTO> {}

    record StepExecutionStarted(Flux<String> tokenStream) implements StartEvent {}

    record StepExecutionFinished(
        StepExecutionResultDTO findings,
        String rawResponse
    ) implements EndEvent<StepExecutionResultDTO> {}

    record AnalysisNegotiationStarted(Flux<String> tokenStream) implements StartEvent {}

    record AnalysisNegotiationFinished(
        AnalysisResultDTO findings,
        String rawResponse,
        NegotiationFinishReason reason
    ) implements EndEvent<AnalysisResultDTO> {}

    record AnalysisVersionCreationStarted(Flux<String> tokenStream) implements StartEvent {}

    record AnalysisVersionCreationFinished(
        AnalysisResultDTO findings,
        String rawResponse
    ) implements EndEvent<AnalysisResultDTO> {}

    record AnalysisVersionCritiqueStarted(Flux<String> tokenStream) implements StartEvent {}

    record AnalysisVersionCritiqueFinished(
        ConclusionCritiqueDTO findings,
        String rawResponse
    ) implements EndEvent<ConclusionCritiqueDTO> {}

}
