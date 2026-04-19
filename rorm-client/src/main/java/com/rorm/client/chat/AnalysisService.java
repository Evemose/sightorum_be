package com.rorm.client.chat;

import com.rorm.DurableRuntime;
import com.rorm.JobSpec;
import com.rorm.ai.swarm.SwarmEventBus;
import com.rorm.ai.swarm.SwarmInput;
import com.rorm.ai.swarm.SwarmStreamEvent;
import com.rorm.ai.swarm.executor.StepExecutionInput;
import com.rorm.client.chat.dto.AnalysisRequest;
import com.rorm.client.metamodel.MetamodelService;
import com.rorm.client.stream.SseEmitterRegistry;
import com.rorm.metamodel.ModelSpace;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnalysisService {

    private final DurableRuntime durableRuntime;
    private final MetamodelService metamodelService;
    private final SwarmEventBus swarmEventBus;
    private final SseEmitterRegistry sseRegistry;

    public String startAnalysis(String schema, AnalysisRequest request) {
        return startAnalysis(schema, metamodelService.getModelSpace(schema),
            request.query(), request.anchors());
    }

    public String startAnalysis(String schema, ModelSpace modelSpace,
                                String query, List<String> anchors) {
        var input = new SwarmInput(query, schema, modelSpace, anchors);
        var runId = "analysis-" + UUID.randomUUID();

        Thread.startVirtualThread(() -> {
            try {
                durableRuntime.submit(runId, new JobSpec(
                    "durableSwarm", "run",
                    new Object[]{input, runId},
                    new String[]{SwarmInput.class.getName(), String.class.getName()}
                ));
            } catch (Exception e) {
                log.error("Analysis run {} failed", runId, e);
            }
        });

        return runId;
    }

    public String startDescriptiveAnalysis(String schema, ModelSpace modelSpace, String query) {
        var input = new SwarmInput(query, schema, modelSpace, List.of());
        var runId = "desc-" + UUID.randomUUID();

        Thread.startVirtualThread(() -> {
            try {
                durableRuntime.submit(runId, new JobSpec(
                    "descPhase", "run",
                    new Object[]{input, runId},
                    new String[]{SwarmInput.class.getName(), String.class.getName()}
                ));
            } catch (Exception e) {
                log.error("Descriptive analysis run {} failed", runId, e);
            } finally {
                swarmEventBus.complete(runId);
            }
        });

        return runId;
    }

    public SseEmitter streamEvents(String runId, String lastEventId) {
        var subscriberTopic = "analysis:" + runId + ":" + UUID.randomUUID().toString().substring(0, 8);
        var emitter = sseRegistry.register(subscriberTopic, null);
        var skipUntil = lastEventId != null ? Long.parseLong(lastEventId) : 0L;

        var disposableRef = new AtomicReference<Disposable>();
        var disposable = swarmEventBus.subscribe(runId)
            .index()
            .subscribe(
                tuple -> {
                    var seq = tuple.getT1() + 1;
                    if (seq <= skipUntil) {
                        return;
                    }
                    sseRegistry.publish(subscriberTopic, eventName(tuple.getT2()),
                        String.valueOf(seq), tuple.getT2());
                },
                error -> {
                    log.error("Event stream error for run {}", runId, error);
                    sseRegistry.error(subscriberTopic, error.getMessage());
                },
                () -> sseRegistry.complete(subscriberTopic)
            );

        disposableRef.set(disposable);
        emitter.onCompletion(() -> disposableRef.get().dispose());
        emitter.onTimeout(() -> disposableRef.get().dispose());
        emitter.onError(_ -> disposableRef.get().dispose());

        return emitter;
    }

    private static String eventName(SwarmStreamEvent event) {
        return switch (event) {
            case SwarmStreamEvent.AgentStarted _ -> "agent_started";
            case SwarmStreamEvent.AgentToken _ -> "agent_token";
            case SwarmStreamEvent.AgentFinished _ -> "agent_finished";
            case SwarmStreamEvent.RunCompleted _ -> "run_completed";
        };
    }
}
