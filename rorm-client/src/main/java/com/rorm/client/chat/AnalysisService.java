package com.rorm.client.chat;

import com.rorm.DurableRuntime;
import com.rorm.JobSpec;
import com.rorm.ai.swarm.SwarmEventBus;
import com.rorm.ai.swarm.SwarmInput;
import com.rorm.ai.swarm.SwarmStreamEvent;
import com.rorm.ai.swarm.SwarmStreamEvent.AgentProgress;
import com.rorm.client.chat.session.AnalysisStatus;
import com.rorm.client.chat.session.SessionAnalysis;
import com.rorm.client.chat.session.SessionService;
import com.rorm.client.stream.SseEmitterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnalysisService {

    private static final int MAX_TOKEN_BYTES = 4096;
    private static final int LIVE_BATCH_MAX = 256;
    private static final Duration LIVE_BATCH_INTERVAL = Duration.ofMillis(100);

    private final DurableRuntime durableRuntime;
    private final SwarmEventBus swarmEventBus;
    private final SseEmitterRegistry sseRegistry;
    private final SessionService sessionService;

    public String startAnalysis(String schema, String query, List<String> anchors) {
        var input = new SwarmInput(query, schema, anchors);
        var runId = "analysis-" + UUID.randomUUID();
        runDurable(runId, new JobSpec(
            "durableSwarm", "run",
            new Object[]{input, runId},
            new String[]{SwarmInput.class.getName(), String.class.getName()}
        ), false);
        return runId;
    }

    /**
     * Spawn the Restate submission on a virtual thread. Persistence of the
     * collected event log is owned by
     * {@link com.rorm.client.chat.session.AnalysisPersistenceCoordinator}
     * — its subscriber holds the bus until {@code RunCompleted} arrives and
     * survives JVM restarts via the no-TTL Valkey pending set.
     */
    private void runDurable(String runId, JobSpec spec, boolean closeBus) {
        Thread.startVirtualThread(() -> {
            try {
                durableRuntime.submit(runId, spec);
            } catch (Exception e) {
                log.error("Run {} failed", runId, e);
            } finally {
                if (closeBus) {
                    swarmEventBus.complete(runId);
                }
            }
        });
    }

    /**
     * Nudge a research run forward by resuming its top-level Restate
     * invocation if it is paused or suspended. Idempotent: a no-op when the
     * invocation is already running. Returns true when a resume was issued.
     */
    public boolean nudge(String runId) {
        var resumed = durableRuntime.nudge(runId);
        log.info("Nudge: runId={} resumed={}", runId, resumed);
        return resumed;
    }

    public String startDescriptiveAnalysis(String schema, String query) {
        var input = new SwarmInput(query, schema, List.of());
        var runId = "desc-" + UUID.randomUUID();
        runDurable(runId, new JobSpec(
            "descPhase", "run",
            new Object[]{input, runId},
            new String[]{SwarmInput.class.getName(), String.class.getName()}
        ), true);
        return runId;
    }

    /**
     * Single uniform endpoint for following a run. The caller never has to
     * choose between "live" and "completed" — this method routes:
     * <ul>
     *   <li>If the run is terminal in the DB and has a persisted event log,
     *       replay events from the DB and complete the stream. Survives any
     *       Redis TTL.</li>
     *   <li>Otherwise subscribe to the live event bus (replay-from-cursor-0
     *       semantics + live tail).</li>
     * </ul>
     */
    public SseEmitter streamEvents(String runId, @Nullable String lastEventId) {
        var skipUntil = lastEventId != null ? Long.parseLong(lastEventId) : 0L;
        return sessionService.findAnalysis(runId)
            .filter(a -> a.getStatus() != AnalysisStatus.RUNNING && a.getEvents() != null)
            .map(a -> replayFromStorage(runId, a, skipUntil))
            .orElseGet(() -> liveStream(runId, skipUntil));
    }

    private SseEmitter replayFromStorage(String runId, SessionAnalysis analysis, long skipUntil) {
        var topic = subscriberTopic(runId);
        var emitter = sseRegistry.register(topic, null);
        var events = sessionService.readEvents(analysis.getEvents());
        Thread.startVirtualThread(() -> {
            try {
                long seq = 0;
                if (events != null) {
                    for (var event : events) {
                        seq++;
                        if (seq <= skipUntil) {
                            continue;
                        }
                        sseRegistry.publish(topic, eventName(event), String.valueOf(seq), event);
                    }
                }
            } finally {
                sseRegistry.complete(topic);
            }
        });
        return emitter;
    }

    private SseEmitter liveStream(String runId, long skipUntil) {
        var topic = subscriberTopic(runId);
        var emitter = sseRegistry.register(topic, null);

        var disposableRef = new AtomicReference<Disposable>();
        var disposable = swarmEventBus.subscribe(runId)
            .bufferTimeout(LIVE_BATCH_MAX, LIVE_BATCH_INTERVAL)
            .filter(batch -> !batch.isEmpty())
            .concatMap(batch -> Flux.fromIterable(
                TokenAggregator.aggregate(batch, MAX_TOKEN_BYTES)))
            .index()
            .subscribe(
                tuple -> {
                    var seq = tuple.getT1() + 1;
                    if (seq <= skipUntil) {
                        return;
                    }
                    sseRegistry.publish(topic, eventName(tuple.getT2()),
                        String.valueOf(seq), tuple.getT2());
                },
                error -> {
                    log.error("Event stream error for run {}", runId, error);
                    sseRegistry.error(topic, error.getMessage());
                },
                () -> sseRegistry.complete(topic)
            );

        disposableRef.set(disposable);
        emitter.onCompletion(() -> disposableRef.get().dispose());
        emitter.onTimeout(() -> disposableRef.get().dispose());
        emitter.onError(_ -> disposableRef.get().dispose());

        return emitter;
    }

    private static String subscriberTopic(String runId) {
        return "analysis:" + runId + ":" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String eventName(SwarmStreamEvent event) {
        return switch (event) {
            case SwarmStreamEvent.AgentStarted _ -> "agent_started";
            case SwarmStreamEvent.AgentToken _ -> "agent_token";
            case SwarmStreamEvent.AgentFinished _ -> "agent_finished";
            case SwarmStreamEvent.AgentQuestion _ -> "agent_question";
            case SwarmStreamEvent.AgentAnswer _ -> "agent_answer";
            case SwarmStreamEvent.RunCompleted _ -> "run_completed";
            case SwarmStreamEvent.RewindStarted _ -> "rewind_started";
            case SwarmStreamEvent.RewindReady _ -> "rewind_ready";
            case AgentProgress _ -> "agent_progress";
        };
    }
}
