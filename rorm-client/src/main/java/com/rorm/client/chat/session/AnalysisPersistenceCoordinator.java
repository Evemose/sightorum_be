package com.rorm.client.chat.session;

import com.rorm.ai.swarm.SwarmEventBus;
import com.rorm.ai.swarm.SwarmStreamEvent;
import com.rorm.client.chat.TokenAggregator;
import com.rorm.client.research.rewind.RewindService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns durable Redis→Postgres event persistence for swarm runs.
 * <p>
 * Each registered analysis spawns a virtual-thread subscriber that blocks
 * on {@link SwarmEventBus#subscribe} until the stream completes (i.e. a
 * {@code RunCompleted} event arrives) and writes the collected events onto
 * the {@code session_analyses} row. The set of runs still needing a
 * subscriber is exactly the rows whose status is {@code RUNNING}; on JVM
 * boot we scan that set and re-spawn one subscriber per row, restoring the
 * subscribers the dead JVM was responsible for.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnalysisPersistenceCoordinator {

    private static final int MAX_TOKEN_BYTES = 4096;

    private final SwarmEventBus swarmEventBus;
    private final SessionService sessionService;
    private final RewindService rewindService;

    private final Set<String> activeSubscribers = ConcurrentHashMap.newKeySet();

    public void track(String runId) {
        if (!activeSubscribers.add(runId)) {
            return;
        }
        Thread.startVirtualThread(() -> drain(runId));
    }

    private void drain(String runId) {
        try {
            var raw = swarmEventBus.subscribe(runId).collectList().block();
            persist(runId, raw);
        } catch (Exception e) {
            log.error("Persistence drain failed for run {}", runId, e);
            sessionService.markAnalysisFailed(runId, e.getMessage(), null);
        } finally {
            activeSubscribers.remove(runId);
        }
    }

    private void persist(String runId, List<SwarmStreamEvent> raw) {
        var aggregated = raw == null ? null : TokenAggregator.aggregate(raw, MAX_TOKEN_BYTES);
        sessionService.markAnalysisSucceeded(runId, aggregated);
        log.info("Persisted {} events for run {}",
            aggregated == null ? 0 : aggregated.size(), runId);
        // The rewind composer reads the just-persisted transcript;
        // start it after the markAnalysisSucceeded write so its
        // `SessionAnalysisRepository.findById` returns the populated
        // row. Eligibility (causal-only) is checked inside.
        rewindService.start(runId);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void resumeOnBoot() {
        var pending = sessionService.findRunningAnalyses();
        if (pending.isEmpty()) {
            return;
        }
        log.info("Resuming {} pending analyses on boot", pending.size());
        pending.forEach(a -> track(a.getRunId()));
    }
}
