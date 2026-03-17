package com.rorm.ml.restate;

import com.fasterxml.jackson.databind.JsonNode;
import com.rorm.DurableRuntime;
import com.rorm.JobSpec;
import dev.restate.client.Client;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.Optional;

@Slf4j
@RequiredArgsConstructor
public class RestateDurableRuntime implements DurableRuntime {

    private final Client restateClient;
    private final RestClient restateAdminClient;

    @Override
    public Object submit(String sessionId, JobSpec spec) {
        return findPausedInvocation(sessionId)
            .map(invocationId -> {
                log.info("Found paused invocation {} for session {}, resuming", invocationId, sessionId);
                var future = restateClient.invocationHandle(invocationId, Object.class)
                    .attachAsync();
                resumeInvocation(invocationId);
                return future.join().response();
            })
            .orElseGet(() -> {
                log.info("No paused invocation for session {}, submitting new job", sessionId);
                return DurableJobServiceClient.fromClient(restateClient, sessionId)
                    .execute(spec);
            });
    }

    private Optional<String> findPausedInvocation(String sessionId) {
        try {
            var query = """
                SELECT id FROM sys_invocation \
                WHERE target_service_name = 'DurableJobService' \
                AND target_service_key = '%s' \
                AND status = 'paused' \
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

    private void resumeInvocation(String invocationId) {
        restateAdminClient.patch()
            .uri("/invocations/{id}/resume", invocationId)
            .retrieve()
            .toBodilessEntity();
    }
}
