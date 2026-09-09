package com.rorm.client.research.rewind;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rorm.ai.ModelSpaceResolver;
import com.rorm.ai.swarm.SwarmEventBus;
import com.rorm.ai.swarm.SwarmStreamEvent;
import com.rorm.client.chat.session.SessionAnalysis;
import com.rorm.client.chat.session.SessionAnalysisRepository;
import com.rorm.client.chat.session.SessionService;
import com.rorm.metamodel.ModelSpace;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Composes the post-run rewind slideshow for a causal analysis.
 *
 * <p>{@code start(runId)} kicks generation off in a background virtual
 * thread, publishes {@code RewindStarted} immediately, then runs the
 * five (or N+2 — one per hypothesis chain plus recon + verdict) tasks
 * concurrently. When all tasks resolve, the assembled {@link RewindDTO}
 * is cached and {@code RewindReady} is published.
 *
 * <p><b>NOTE</b>. The per-section narration in this initial revision is
 * derived deterministically from the persisted agent transcripts via
 * {@link RewindNarrator}. The actual Sonnet calls — one per section, in
 * parallel, with manager-facing prompts — are the obvious next step;
 * the seams are in place ({@code RewindNarrator.compose*}) so swapping
 * in {@code AgentChatService} calls is a localized change.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RewindService {

    private final SessionAnalysisRepository analysisRepository;
    private final SessionService sessionService;
    private final ModelSpaceResolver modelSpaceResolver;
    private final SwarmEventBus eventBus;
    private final ObjectMapper objectMapper;
    private final RewindNarrator narrator;

    /** In-memory cache. Rewinds are cheap to regenerate from the
     *  persisted transcript and we'd rather pay that cost than
     *  introduce a new persistence table for the demo path. */
    private final Map<String, RewindDTO> cache = new ConcurrentHashMap<>();
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private final Map<String, Future<?>> workerFutures = new ConcurrentHashMap<>();
    private final java.util.concurrent.ExecutorService workers =
        Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("rewind-", 0).factory());

    /**
     * Eligibility: causal {@code analysis-*} runs only. Descriptive
     * runs reuse the existing dashboard tab content; mock and other
     * shapes don't have the per-hypothesis structure the rewind
     * narrates.
     */
    public boolean isEligible(String runId) {
        return runId != null && runId.startsWith("analysis-");
    }

    /** Begin generation if eligible and not already cached / running. */
    public void start(String runId) {
        if (!isEligible(runId)) return;
        if (cache.containsKey(runId)) return;
        if (!inFlight.add(runId)) return;

        var startedAt = Instant.now().toString();
        cache.put(runId, new RewindDTO(
            RewindDTO.Status.IN_PROGRESS, startedAt, null, null, null, List.of(), null));
        eventBus.publish(runId, new SwarmStreamEvent.RewindStarted(startedAt));

        var future = workers.submit(() -> {
            try {
                var dto = compose(runId, startedAt);
                cache.put(runId, dto);
                eventBus.publish(runId, new SwarmStreamEvent.RewindReady(
                    Optional.ofNullable(dto.readyAt()).orElse(Instant.now().toString())));
            } catch (Exception e) {
                log.error("[rewind] generation failed for {}: {}", runId, e.toString());
                cache.put(runId, new RewindDTO(
                    RewindDTO.Status.FAILED, startedAt, Instant.now().toString(),
                    e.getMessage(), null, List.of(), null));
                eventBus.publish(runId, new SwarmStreamEvent.RewindReady(Instant.now().toString()));
            } finally {
                workerFutures.remove(runId);
                inFlight.remove(runId);
            }
        });
        workerFutures.put(runId, future);
    }

    /**
     * Read-through getter used by the FE. If the cache is empty and
     * the run is eligible + finished, lazily kick off generation so a
     * page reload after server restart can still produce the slides.
     */
    public Optional<RewindDTO> get(String runId) {
        var cached = cache.get(runId);
        if (cached != null) return Optional.of(cached);
        if (!isEligible(runId)) return Optional.empty();
        var analysis = analysisRepository.findById(runId).orElse(null);
        if (analysis == null) return Optional.empty();
        start(runId);
        return Optional.ofNullable(cache.get(runId));
    }

    // ─── Composition ──────────────────────────────────────────────

    private RewindDTO compose(String runId, String startedAt) {
        var analysis = analysisRepository.findById(runId)
            .orElseThrow(() -> new IllegalStateException("no persisted analysis for " + runId));

        // Decode the event stream into a simple "agent transcript"
        // grouping (kind + tags + rawResponse). The narrator reads
        // from this — keeping the source-of-truth in one place makes
        // it easy to swap the narrator implementation later.
        var transcripts = decodeTranscripts(analysis);

        // Resolve schema + ModelSpace for the run's session so each
        // narrator call goes through AgentChatService with the same
        // data context the rest of the analysis ran under. The
        // rewind doesn't query data itself, but ChatRequest is typed
        // around (schema, modelSpace) — supplying them keeps the
        // tool-context wiring uniform with every other typed chat
        // call in the system.
        var schema = sessionService.findSchemaName(analysis.getSessionId())
            .orElseThrow(() -> new IllegalStateException(
                "no schema bound to session " + analysis.getSessionId()));
        ModelSpace modelSpace = modelSpaceResolver.resolve(schema);

        // Fan out every section concurrently — recon + N hypotheses +
        // verdict — so the wall-clock tracks the slowest call rather
        // than the sum. Per-section LLM failures fall back to
        // deterministic placeholders inside the narrator.
        var composed = narrator.composeAll(analysis, transcripts, schema, modelSpace);

        return new RewindDTO(
            RewindDTO.Status.READY,
            startedAt,
            Instant.now().toString(),
            null,
            composed.recon(),
            composed.chains(),
            composed.verdict()
        );
    }

    /**
     * Flatten the persisted event log into a per-agent record. We
     * only care about {@code agent_started} (to learn each agent's
     * kind + tags) and {@code agent_finished} (to capture the
     * resolved transcript text + structured output) — the narrator
     * doesn't need token-level granularity.
     */
    private Map<String, RewindNarrator.AgentTranscript> decodeTranscripts(SessionAnalysis analysis) {
        var out = new LinkedHashMap<String, RewindNarrator.AgentTranscript>();
        var events = analysis.getEvents();
        if (events == null || !events.isArray()) return out;
        for (JsonNode ev : events) {
            var type = ev.path("type").asText("");
            switch (type) {
                case "AGENT_STARTED" -> {
                    var id    = ev.path("eventId");
                    var token = id.path("token").asText("");
                    var kind  = ev.path("kind").asText("");
                    var tags  = new LinkedHashMap<String, String>();
                    var tagNode = id.path("tags");
                    if (tagNode.isObject()) {
                        tagNode.fields().forEachRemaining(e -> tags.put(e.getKey(), e.getValue().asText("")));
                    }
                    out.putIfAbsent(token, new RewindNarrator.AgentTranscript(token, kind, tags, "", null));
                }
                case "AGENT_FINISHED" -> {
                    var token   = ev.path("eventId").path("token").asText("");
                    var existing = out.get(token);
                    if (existing == null) continue;
                    var raw   = ev.path("rawResponse").asText("");
                    var outNd = ev.path("output");
                    out.put(token, existing.withResolution(raw, outNd.isMissingNode() ? null : outNd));
                }
                default -> { /* ignore tokens, progress, q/a — narrator works off finished agents */ }
            }
        }
        // Drop agents that never finished — they have no content to
        // narrate and would create empty slides.
        out.entrySet().removeIf(e -> e.getValue().rawResponse().isEmpty());
        return List.copyOf(out.entrySet()).stream()
            .collect(
                LinkedHashMap::new,
                (acc, e) -> acc.put(e.getKey(), e.getValue()),
                LinkedHashMap::putAll
            );
    }

    /** Test/admin escape hatch: clear a cached rewind so the next
     *  read regenerates from scratch. Useful when the narrator
     *  implementation changes and we want a forced refresh.
     *
     *  <p>Also cancels any in-flight worker and frees the {@code inFlight}
     *  slot — without this, a stuck worker would keep regenerate calls
     *  bailing on the in-flight guard until the JVM restarts. Cancelling
     *  with {@code mayInterruptIfRunning=true} interrupts hung HTTP I/O
     *  inside the narrator's section calls, which {@code RewindNarrator}
     *  observes and bails out of cleanly. */
    public void invalidate(String runId) {
        cache.remove(runId);
        var pending = workerFutures.remove(runId);
        if (pending != null) {
            pending.cancel(true);
        }
        inFlight.remove(runId);
    }

    /** Helper for code paths that want to enumerate every entry the
     *  service is aware of (e.g. health checks, debug pages). The
     *  returned list is a snapshot. */
    public List<String> knownRuns() {
        return new ArrayList<>(cache.keySet());
    }
}
