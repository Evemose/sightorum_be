package com.rorm.ai.swarm;

import com.rorm.ai.chat.StreamToken;
import com.rorm.ai.swarm.SwarmEvent.EndEvent;
import com.rorm.ai.swarm.SwarmEvent.StartEvent;
import com.rorm.ai.swarm.dto.*;
import com.rorm.ml.dto.PipelineSpecRequest;
import reactor.core.publisher.Flux;

public sealed interface SwarmEvent permits StartEvent, EndEvent {

    EventId id();

    sealed interface StartEvent extends SwarmEvent permits
        DurableScoutStarted,
        DomainResearcherStarted,
        GeneratorStarted,
        ScepticStarted,
        RebuttalStarted,
        CompilerStarted,
        ForensicPathologistStarted {

        Flux<StreamToken> tokenStream();
    }

    sealed interface EndEvent<T> extends SwarmEvent permits
        DurableScoutFinished,
        DomainResearcherFinished,
        GeneratorFinished,
        ScepticFinished,
        RebuttalFinished,
        CompilerFinished,
        ForensicPathologistFinished {

        T findings();

        String rawResponse();
    }

    record DurableScoutStarted(EventId id, Flux<StreamToken> tokenStream) implements StartEvent {}

    record DurableScoutFinished(EventId id, ScoutAnalysisDTO findings, String rawResponse)
        implements EndEvent<ScoutAnalysisDTO> {}

    record DomainResearcherStarted(EventId id, Flux<StreamToken> tokenStream) implements StartEvent {}

    record DomainResearcherFinished(EventId id, DomainResearchDTO findings, String rawResponse)
        implements EndEvent<DomainResearchDTO> {}

    record GeneratorStarted(EventId id, Flux<StreamToken> tokenStream) implements StartEvent {}

    record GeneratorFinished(EventId id, HypothesisGenerationDTO findings, String rawResponse)
        implements EndEvent<HypothesisGenerationDTO> {}

    record ScepticStarted(EventId id, Flux<StreamToken> tokenStream) implements StartEvent {}

    record ScepticFinished(EventId id, ScepticReviewDTO findings, String rawResponse)
        implements EndEvent<ScepticReviewDTO> {}

    record RebuttalStarted(EventId id, Flux<StreamToken> tokenStream) implements StartEvent {}

    record RebuttalFinished(EventId id, HypothesisGenerationDTO findings, String rawResponse)
        implements EndEvent<HypothesisGenerationDTO> {}

    record CompilerStarted(EventId id, Flux<StreamToken> tokenStream) implements StartEvent {}

    record CompilerFinished(EventId id, PipelineSpecRequest findings, String rawResponse)
        implements EndEvent<PipelineSpecRequest> {}

    record ForensicPathologistStarted(EventId id, Flux<StreamToken> tokenStream) implements StartEvent {}

    record ForensicPathologistFinished(EventId id, ForensicDiagnosisDTO findings, String rawResponse)
        implements EndEvent<ForensicDiagnosisDTO> {}

}
