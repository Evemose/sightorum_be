package com.rorm.ml.restate;

import com.fasterxml.jackson.databind.JsonNode;
import com.rorm.*;
import com.rorm.ml.restate.RestateDurableFuture.Delegated;
import dev.restate.client.Client;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Runtime that dispatches jobs to Restate and polls for completion. Uses
 * {@code send() + getOutput()} polling instead of a blocking {@code execute()}
 * long-poll: a single hours-long HTTP call is fragile because idle timeouts in
 * proxies / load balancers / the Restate ingress can silently drop the
 * connection, leaving {@link java.net.http.HttpClient} hanging forever while
 * the invocation has already completed server-side. Short poll calls are
 * immune to that class of failure.
 */
@Slf4j
@RequiredArgsConstructor
public class RestateDurableRuntime implements DurableRuntime {

    private final Client restateClient;
    private final RestClient restateAdminClient;
    private final Duration initialPollInterval;
    private final Duration maxPollInterval;

    /**
     * Non-blocking dispatch: returns a {@link DurableFuture} backed by a
     * Restate {@code ctx.serviceClient(...).execute(...)} call. Must be
     * invoked from inside a Restate handler (i.e. a {@link RestateStepJournal}
     * is bound as the current journal); the resulting future participates in
     * the parent invocation's journal and can be combined via
     * {@link DurableFuture#all} for parallel sub-invocations.
     */
    @Override
    public DurableFuture<Object> submitAsync(String sessionId, JobSpec spec) {
        var journal = StepJournal.current();
        if (!(journal instanceof RestateStepJournal restate)) {
            throw new IllegalStateException(
                "submitAsync requires a RestateStepJournal in scope; got "
                + journal.getClass().getName());
        }
        var future = DurableJobServiceClient.fromContext(restate.context(), sessionId).execute(spec);
        return new Delegated<>(future, UUID.randomUUID().toString());
    }

    @Override
    public Object submit(String sessionId, JobSpec spec) {
        var journal = StepJournal.current();
        if (journal instanceof RestateStepJournal restate) {
            // Inside a Restate handler: use ctx-bound client so the sub-invocation
            // is journaled. On parent replay/resume we get the same DurableFuture
            // back instead of submitting a duplicate child.
            return DurableJobServiceClient.fromContext(restate.context(), sessionId)
                .execute(spec)
                .await();
        }
        var invocationId = findPausedInvocation(sessionId)
            .map(id -> resumeExisting(id, sessionId))
            .orElseGet(() -> submitNew(sessionId, spec));
        return awaitResult(invocationId);
    }

    private String resumeExisting(String invocationId, String sessionId) {
        log.info("Found paused invocation {} for session {}, resuming", invocationId, sessionId);
        resumeInvocation(invocationId);
        return invocationId;
    }

    private String submitNew(String sessionId, JobSpec spec) {
        log.info("No paused invocation for session {}, submitting new job", sessionId);
        var sendResponse = DurableJobServiceClient.fromClient(restateClient, sessionId)
            .send()
            .execute(spec);
        log.info("Submitted invocation {} for session {}", sendResponse.invocationId(), sessionId);
        return sendResponse.invocationId();
    }

    private Optional<String> findPausedInvocation(String sessionId) {
        try {
            var query = """
                SELECT id FROM sys_invocation \
                WHERE target_service_name = 'DurableJobService' \
                AND target_service_key = '%s' \
                AND (status = 'paused' OR status = 'suspended') \
                LIMIT 1""".formatted(sessionId);

            var result = restateAdminClient.post()
                .uri("/query")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(Map.of("query", query))
                .retrieve()
                .body(JsonNode.class);

            if (result != null) {
                var rows = result.path("rows");
                if (rows.isArray() && !rows.isEmpty()) {
                    return Optional.of(rows.get(0).path("id").asText());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to query paused invocations for session {}", sessionId, e);
        }
        return Optional.empty();
    }

    private Ready pollOnce(Client.InvocationHandle<Object> handle, String invocationId) {
        try {
            var output = handle.getOutput().response();
            return output.isReady() ? new Ready(output.getValue()) : null;
        } catch (Exception e) {
            log.warn("[durable] poll failed for invocation {}: {}", invocationId, e.getMessage());
            return null;
        }
    }

    private Object awaitResult(String invocationId) {
        var handle = restateClient.invocationHandle(invocationId, Object.class);
        var interval = initialPollInterval;
        while (true) {
            var ready = pollOnce(handle, invocationId);
            if (ready != null) {
                return ready.value();
            }
            if (isPaused(invocationId)) {
                try {
                    resumeInvocation(invocationId);
                    log.warn("[durable] invocation {} was paused; resume issued", invocationId);
                } catch (Exception e) {
                    throw new IllegalStateException(
                        "Restate invocation " + invocationId
                        + " is paused and resume failed; check server logs for the underlying handler error",
                        e);
                }
            }
            sleepInterruptibly(interval);
            interval = nextInterval(interval);
        }
    }

    private static void sleepInterruptibly(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while awaiting invocation", e);
        }
    }

    private Duration nextInterval(Duration current) {
        var doubled = current.multipliedBy(2);
        return doubled.compareTo(maxPollInterval) > 0 ? maxPollInterval : doubled;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> AwakableHandle<T> handle(String awakableId, Class<T> resultType) {
        var handle = restateClient.awakeableHandle(awakableId);
        return new AwakableHandle<T>() {
            @Override
            public void resolve(T result) {
                handle.resolve(resultType, result);
            }

            @Override
            public void reject(String reason) {
                handle.reject(reason);
            }
        };
    }

    private boolean isPaused(String invocationId) {
        try {
            var query = """
                SELECT status FROM sys_invocation \
                WHERE id = '%s' \
                LIMIT 1""".formatted(invocationId);
            var result = restateAdminClient.post()
                .uri("/query")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(Map.of("query", query))
                .retrieve()
                .body(JsonNode.class);
            if (result == null) {
                return false;
            }
            var rows = result.path("rows");
            if (!rows.isArray() || rows.isEmpty()) {
                return false;
            }
            var status = rows.get(0).path("status").asText("");
            return "paused".equals(status) || "suspended".equals(status);
        } catch (Exception e) {
            log.warn("[durable] status query failed for invocation {}: {}", invocationId, e.getMessage());
            return false;
        }
    }

    private void resumeInvocation(String invocationId) {
        restateAdminClient.patch()
            .uri("/invocations/{id}/resume", invocationId)
            .retrieve()
            .toBodilessEntity();
    }

    private record Ready(Object value) {}
}
