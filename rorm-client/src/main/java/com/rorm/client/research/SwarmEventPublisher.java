package com.rorm.client.research;

import com.rorm.ai.swarm.SwarmEvent;
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
            case SwarmEvent.ScoutStarted _ -> "scout-started";
            case SwarmEvent.ScoutFinished _ -> "scout-finished";
            case SwarmEvent.PlanNegotiationStarted _ -> "plan-negotiation-started";
            case SwarmEvent.PlanNegotiationFinished _ -> "plan-negotiation-finished";
            case SwarmEvent.PlanVersionCreationStarted _ -> "plan-version-creation-started";
            case SwarmEvent.PlanVersionCreationFinished _ -> "plan-version-creation-finished";
            case SwarmEvent.PlanVersionCritiqueStarted _ -> "plan-version-critique-started";
            case SwarmEvent.PlanVersionCritiqueFinished _ -> "plan-version-critique-finished";
            case SwarmEvent.BranchExecutionStarted _ -> "branch-execution-started";
            case SwarmEvent.BranchExecutionFinished _ -> "branch-execution-finished";
            case SwarmEvent.StepExecutionStarted _ -> "step-execution-started";
            case SwarmEvent.StepExecutionFinished _ -> "step-execution-finished";
            case SwarmEvent.AnalysisNegotiationStarted _ -> "analysis-negotiation-started";
            case SwarmEvent.AnalysisNegotiationFinished _ -> "analysis-negotiation-finished";
            case SwarmEvent.AnalysisVersionCreationStarted _ -> "analysis-version-creation-started";
            case SwarmEvent.AnalysisVersionCreationFinished _ -> "analysis-version-creation-finished";
            case SwarmEvent.AnalysisVersionCritiqueStarted _ -> "analysis-version-critique-started";
            case SwarmEvent.AnalysisVersionCritiqueFinished _ -> "analysis-version-critique-finished";
        };
    }
}
