package com.rorm.client.chat.session;

import com.rorm.ai.swarm.SwarmEventBus;
import com.rorm.ai.swarm.SwarmStreamEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * One-shot backfill: walks every {@code session_analyses} row still marked
 * {@code RUNNING}, replays its event stream from Redis, and persists the
 * collected log onto the row before the stream's TTL expires.
 * <p>
 * Gated by {@code rorm.client.analysis.backfill.enabled}. Set true once,
 * restart, then remove.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "rorm.client.analysis.backfill", name = "enabled", havingValue = "true")
public class AnalysisBackfillRunner implements CommandLineRunner {

    /**
     * Per-run timeout. Short enough that a fully-purged stream gives up
     * quickly; long enough that an active stream with a pending RunCompleted
     * still drains.
     */
    private static final Duration PER_RUN_TIMEOUT = Duration.ofSeconds(10);

    private final SessionService sessionService;
    private final SwarmEventBus swarmEventBus;

    @Override
    public void run(String... args) {
        var pending = sessionService.findRunningAnalyses();
        if (pending.isEmpty()) {
            log.info("[backfill] no RUNNING analyses to recover");
            return;
        }
        log.info("[backfill] attempting to recover {} runs", pending.size());

        int succeeded = 0;
        int expired = 0;
        int indeterminate = 0;

        for (var a : pending) {
            var events = drain(a.getRunId());
            if (events == null) {
                indeterminate++;
                log.info("[backfill] run {} indeterminate (timed out, leaving as RUNNING)", a.getRunId());
                continue;
            }
            if (events.isEmpty()) {
                expired++;
                sessionService.markAnalysisFailed(a.getRunId(),
                    "Event stream expired before backfill could recover the run", List.of());
                log.info("[backfill] run {} marked FAILED (events expired)", a.getRunId());
                continue;
            }
            sessionService.markAnalysisSucceeded(a.getRunId(), events);
            succeeded++;
            log.info("[backfill] run {} recovered with {} events", a.getRunId(), events.size());
        }

        log.info("[backfill] done: succeeded={}, expired={}, indeterminate={}",
            succeeded, expired, indeterminate);
    }

    private List<SwarmStreamEvent> drain(String runId) {
        try {
            return swarmEventBus.subscribe(runId).collectList().block(PER_RUN_TIMEOUT);
        } catch (Exception e) {
            log.warn("[backfill] error draining run {}: {}", runId, e.getMessage());
            return null;
        }
    }
}
