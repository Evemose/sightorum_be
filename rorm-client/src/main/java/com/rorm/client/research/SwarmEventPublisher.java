package com.rorm.client.research;

import com.rorm.ai.swarm.SwarmEvent;
import com.rorm.ai.swarm.SwarmEvent.*;
import com.rorm.client.stream.SseEmitterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Publishes swarm research events to SSE subscribers.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SwarmEventPublisher {

    private final SseEmitterRegistry registry;
    private final AtomicLong eventCounter = new AtomicLong(0);

    /**
     * Publishes a swarm event to all subscribers of the session.
     */
    public void publish(UUID sessionId, SwarmEvent event) {
        var topic = buildSwarmTopic(sessionId);
        var eventId = generateEventId();
        var eventType = determineEventType(event);

        registry.publish(topic, eventType, eventId, event);
        log.debug("Published swarm event {} to session {}", eventType, sessionId);
    }

    private String buildSwarmTopic(UUID sessionId) {
        return "swarm:" + sessionId;
    }

    private String generateEventId() {
        return String.valueOf(eventCounter.incrementAndGet());
    }

    private String determineEventType(SwarmEvent event) {
        return switch (event) {
            case ScoutStarted _ -> "scout-started";
            case ScoutFinished _ -> "scout-finished";
            case PlanNegotiationStarted _ -> "plan-negotiation-started";
            case PlanNegotiationFinished _ -> "plan-negotiation-finished";
            case PlanVersionCreationStarted _ -> "plan-version-creation-started";
            case PlanVersionCreationFinished _ -> "plan-version-creation-finished";
            case PlanVersionCritiqueStarted _ -> "plan-version-critique-started";
            case PlanVersionCritiqueFinished _ -> "plan-version-critique-finished";
            case BranchExecutionStarted _ -> "branch-execution-started";
            case BranchExecutionFinished _ -> "branch-execution-finished";
            case StepExecutionStarted _ -> "step-execution-started";
            case StepExecutionFinished _ -> "step-execution-finished";
            case AnalysisNegotiationStarted _ -> "analysis-negotiation-started";
            case AnalysisNegotiationFinished _ -> "analysis-negotiation-finished";
            case AnalysisVersionCreationStarted _ -> "analysis-version-creation-started";
            case AnalysisVersionCreationFinished _ -> "analysis-version-creation-finished";
            case AnalysisVersionCritiqueStarted _ -> "analysis-version-critique-started";
            case AnalysisVersionCritiqueFinished _ -> "analysis-version-critique-finished";
            case StepTrainingAwaitStarted _ -> "step-training-await-started";
            case StepTrainingCompleted _ -> "step-training-completed";
        };
    }
}
