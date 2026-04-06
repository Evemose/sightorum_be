package com.rorm.ai.swarm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.rorm.ai.anthropic.ScriptedAnthropicClient;
import com.rorm.dataimport.pipeline.profile.SchemaProfile;
import com.rorm.dataimport.pipeline.profile.SchemaProfileStore;
import com.rorm.metamodel.ModelSpace;
import com.rorm.ml.PipelineSpecConverter;
import com.rorm.ml.dto.CausalVerificationJobRequest;
import com.rorm.ml.dto.PipelineSpecRequest;
import com.rorm.ml.restate.RestateStepJournal;
import dev.restate.sdk.ObjectContext;
import dev.restate.sdk.annotation.Exclusive;
import dev.restate.sdk.annotation.Name;
import dev.restate.sdk.endpoint.definition.InvocationRetryPolicy;
import dev.restate.sdk.springboot.RestateServiceConfigurator;
import dev.restate.sdk.springboot.RestateVirtualObject;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test-only additions to the real application context. Provides:
 * <ul>
 *   <li>An {@link AnthropicClient} pointed at WireMock with call counting</li>
 *   <li>A {@link RestateVirtualObject} handler that runs the {@link DurableSwarm} pipeline</li>
 *   <li>A retry policy that pauses on failure (matching production {@code DurableJobService})</li>
 * </ul>
 * <p>
 * The real app ({@code RormClientApplication}) provides everything else —
 * {@link com.rorm.ai.chat.AiChatService}, {@link com.rorm.ml.MlTrainingService},
 * {@link com.rorm.ml.PipelineSpecConverter}, etc.
 */
@TestConfiguration
public class DurableSwarmRestateTestApp {

    public static final AtomicInteger LLM_CALL_COUNT = new AtomicInteger();

    @Bean
    public SchemaProfileStore schemaProfileStore() {
        return new SchemaProfileStore() {
            private final ConcurrentHashMap<String, SchemaProfile> store = new ConcurrentHashMap<>();

            @Override
            public void store(String schema, SchemaProfile profile) {
                store.put(schema, profile);
            }

            @Override
            public Optional<SchemaProfile> get(String schema) {
                return Optional.ofNullable(store.get(schema));
            }

            @Override
            public void remove(String schema) {
                store.remove(schema);
            }
        };
    }

    @Bean
    @Primary
    AnthropicClient testAnthropicClient(@Value("${wiremock.base-url:}") String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return ScriptedAnthropicClient.withCallCounter(LLM_CALL_COUNT);
        }
        var real = AnthropicOkHttpClient.builder().apiKey("test-key").baseUrl(baseUrl).build();
        var messageService = ScriptedAnthropicClient.proxy(
            com.anthropic.services.blocking.MessageService.class, (method, args) -> {
                if (method.equals("create")) {
                    var result = real.messages().create((com.anthropic.models.messages.MessageCreateParams) args[0]);
                    var count = LLM_CALL_COUNT.incrementAndGet();
                    writeCallCount(count);
                    return result;
                }
                if (method.equals("createStreaming")) {
                    LLM_CALL_COUNT.incrementAndGet();
                    return real.messages().createStreaming((com.anthropic.models.messages.MessageCreateParams) args[0]);
                }
                return null;
            });
        return ScriptedAnthropicClient.proxy(
            AnthropicClient.class, (method, _) ->
                method.equals("messages") ? messageService : null);
    }

    static void writeCallCount(int count) {
        var dir = System.getProperty("blackbox.signal.dir");
        if (dir != null) {
            try {
                java.nio.file.Files.writeString(
                    java.nio.file.Path.of(dir, "llm_calls.txt"),
                    String.valueOf(count));
            } catch (Exception ignored) {
            }
        }
    }

    @Bean
    @Primary
    PipelineSpecConverter pipelineSpecConverter() {
        return new PipelineSpecConverter(null, null) {
            @Override
            public CausalVerificationJobRequest convert(
                PipelineSpecRequest spec, String reason, ModelSpace ms, String schema) {
                return CausalVerificationJobRequest.builder()
                    .hypothesisId(spec.hypothesisId()).treatment(spec.treatment())
                    .outcome(spec.outcome()).treatmentForm(spec.treatmentForm())
                    .dagEdges("T -> Y").dsepThreshold(0.03).adjustmentSet(List.of("W1"))
                    .estimationVariants(List.of(Map.of("id", "primary")))
                    .gates(Map.of("nuisance_r2", Map.of("outcome_abort", 0.01)))
                    .sensitivity(Map.of()).residualChecks(Map.of()).rangeChecks(Map.of()).build();
            }
        };
    }

    @Bean
    @Primary
    DurableSwarmConfig durableSwarmConfig() {
        return SwarmTestFixtures.testConfig();
    }

    @Bean
    RestateServiceConfigurator swarmHandlerConfig() {
        return sd -> sd.invocationRetryPolicy(
            InvocationRetryPolicy.builder()
                .maxAttempts(1)
                .onMaxAttempts(InvocationRetryPolicy.OnMaxAttempts.PAUSE)
                .initialInterval(java.time.Duration.ofMillis(1))
                .build());
    }

    @RestateVirtualObject(configuration = "swarmHandlerConfig")
    @Name("SwarmHandler")
    @RequiredArgsConstructor
    public static class SwarmHandler {

        private final DurableSwarm swarm;

        @Exclusive
        public String run(ObjectContext ctx, String ignored) throws Exception {
            var journal = new RestateStepJournal(ctx);
            var result = ScopedValue.where(com.rorm.StepJournal.CURRENT, journal)
                .call(() -> swarm.run(SwarmTestFixtures.defaultInput()));
            return new com.fasterxml.jackson.databind.ObjectMapper()
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                .writeValueAsString(result);
        }
    }
}
