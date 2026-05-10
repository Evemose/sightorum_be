package com.rorm.ai.swarm.phase;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rorm.DurableRuntime;
import com.rorm.JobSpec;
import com.rorm.ai.swarm.*;
import com.rorm.ai.swarm.executor.StepExecutionInput;
import com.rorm.viz.dto.Digest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Descriptive phase: submits the descriptive-agent executor as a durable
 * invocation that turns a user question into a multi-page {@link Digest}
 * (archetype classification + tool calls + structurized envelope).
 * Standalone phase — not chained after recon/gen/compile.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DescPhase {

    private static final TypeReference<StepOutput<Digest>> DIGEST_REF = new TypeReference<>() {};

    private final DurableRuntime runtime;
    private final ObjectMapper mapper;
    private final DurableSwarmConfig config;

    public Output run(SwarmInput input, String runId) {
        return ScopedValue.where(PhaseScope.RUN_ID, runId).call(() -> doRun(input));
    }

    private Output doRun(SwarmInput input) {
        if (input.userQuery() == null || input.userQuery().isBlank()) {
            throw new IllegalArgumentException("DescPhase requires a non-blank userQuery");
        }
        if (input.schema() == null || input.schema().isBlank()) {
            throw new IllegalArgumentException("DescPhase requires a non-blank schema");
        }
        var id = EventId.root("descriptive-agent", ContentHash.of(Map.of(
            "kind", "descriptive-agent",
            "schema", input.schema(),
            "query", input.userQuery())));
        var prompt = config.descriptiveAgent().userPromptTemplate()
            .replace("{{USER_QUERY}}", input.userQuery());
        var stepInput = StepExecutionInput.builder()
            .eventId(id).userPrompt(prompt).schema(input.schema()).runId(PhaseScope.runId())
            .build();

        log.info("[swarm] Descriptive agent for query: {}", input.userQuery());
        var sessionId = "descriptiveAgentExecutor-" + id.token();
        var result = mapper.convertValue(
            runtime.submit(sessionId, new JobSpec(
                "descriptiveAgentExecutor", "execute",
                new Object[]{stepInput},
                new String[]{StepExecutionInput.class.getName()}
            )), DIGEST_REF);
        return new Output(result);
    }

    public record Output(StepOutput<Digest> digest) {}
}
