package com.rorm.ai.swarm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.rorm.CompletableDurableFuture;
import com.rorm.DurableFuture;
import com.rorm.StepJournal;
import com.rorm.ai.MetamodelContextBuilder;
import com.rorm.ai.anthropic.AnthropicParamsBuilder;
import com.rorm.ai.anthropic.JournaledAnthropicChatModel;
import com.rorm.ai.anthropic.TokenThrottle;
import com.rorm.ai.anthropic.TokenThrottleProperties;
import com.rorm.ai.chat.*;
import com.rorm.ai.prompt.AgentPromptBuilder;
import com.rorm.ai.prompt.PromptPlaceholders;
import com.rorm.metamodel.ModelSpace;
import com.rorm.ml.MlTrainingService;
import com.rorm.ml.PipelineSpecConverter;
import com.rorm.ml.dto.CausalVerificationJobRequest;
import com.rorm.ml.dto.PipelineSpecRequest;
import com.rorm.ml.stream.JobCompletionHandler;
import com.rorm.ml.stream.JobEvent;
import com.rorm.ml.stream.JobEventType;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.wiremock.integrations.testcontainers.WireMockContainer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

import static com.github.tomakehurst.wiremock.client.WireMock.configureFor;

/**
 * In-memory E2E test for {@link DurableSwarm} — verifies the full pipeline
 * wiring with {@link StepJournal.InMemory} and WireMock for HTTP.
 */
@DisplayName("DurableSwarm in-memory E2E")
@SpringBootTest(classes = DurableSwarmE2ETest.TestApp.class)
@Testcontainers
class DurableSwarmE2ETest {

    @Container
    static final WireMockContainer wireMock = new WireMockContainer("wiremock/wiremock:3.12.1");
    @Autowired
    DurableSwarm swarm;
    WireMock wireMockClient;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("anthropic.api-key", () -> "test-key");
        registry.add("anthropic.base-url", wireMock::getBaseUrl);
        registry.add("wiremock.base-url", wireMock::getBaseUrl);
    }

    @BeforeEach
    void setupStubs() {
        configureFor(wireMock.getHost(), wireMock.getFirstMappedPort());
        wireMockClient = new WireMock(wireMock.getHost(), wireMock.getFirstMappedPort());
        SwarmTestFixtures.registerAllStubs();
    }

    @Test
    @DisplayName("full pipeline executes all phases with correct data flow and fanout")
    void fullPipelineExecutionWithFanout() throws Exception {
        var result = ScopedValue.where(StepJournal.CURRENT, StepJournal.DEFAULT)
            .call(() -> swarm.run(SwarmTestFixtures.defaultInput()));

        SwarmTestFixtures.assertFullPipelineResult(result);
        SwarmTestFixtures.verifyWireMockDataFlow(wireMockClient);
    }

    // ── Test-only Spring configuration ───────────────────────────────────

    @Configuration
    static class TestApp {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }

        @Bean
        AnthropicClient anthropicClient(@Value("${wiremock.base-url}") String baseUrl) {
            return AnthropicOkHttpClient.builder().apiKey("test-key").baseUrl(baseUrl).build();
        }

        @Bean
        AnthropicParamsBuilder paramsBuilder(ObjectMapper om) {
            return new AnthropicParamsBuilder(om);
        }

        @Bean
        TokenThrottle tokenThrottle() {
            return new TokenThrottle(new TokenThrottleProperties(
                false, 0, BigDecimal.ZERO, BigDecimal.ZERO,
                TokenThrottleProperties.TokenCountStrategy.UNCACHED_ONLY));
        }

        @Bean
        ChatModel chatModel(AnthropicClient c, AnthropicParamsBuilder p, TokenThrottle t) {
            return new JournaledAnthropicChatModel(c, c, p, t, ObservationRegistry.NOOP);
        }

        @Bean
        ChatClient chatClient(ChatModel m) {
            return ChatClient.builder(m).build();
        }

        @Bean
        ChatMemoryRepository chatMemoryRepository() {
            return new ChatMemoryRepository() {
                private final Map<String, List<Message>> store = new LinkedHashMap<>();

                @Override
                public List<String> findConversationIds() {
                    return new ArrayList<>(store.keySet());
                }

                @Override
                public List<Message> findByConversationId(String id) {
                    return store.getOrDefault(id, List.of());
                }

                @Override
                public void saveAll(String id, List<Message> msgs) {
                    store.put(id, new ArrayList<>(msgs));
                }

                @Override
                public void deleteByConversationId(String id) {
                    store.remove(id);
                }
            };
        }

        @Bean
        MetamodelContextBuilder metamodelContextBuilder() {
            return new MetamodelContextBuilder();
        }

        @Bean
        PromptPlaceholders promptPlaceholders(MetamodelContextBuilder b) {
            return new PromptPlaceholders(b);
        }

        @Bean
        AgentPromptBuilder agentPromptBuilder(PromptPlaceholders p) {
            return new AgentPromptBuilder(p);
        }

        @Bean
        ToolGroupResolver toolGroupResolver() {
            return new ToolGroupResolver(null, null, null, null, null, null, null, null, null,
                null, null, null, null);
        }

        @Bean
        ChatRequestPreprocessor preprocessor(AgentPromptBuilder a, PromptPlaceholders p, ToolGroupResolver r) {
            return new ChatRequestPreprocessor(a, p, r);
        }

        @Bean
        AiChatService aiChatService(ChatClient c, ChatMemoryManager m, ChatRequestPreprocessor p) {
            return new AgentChatService(c, m, p);
        }

        @Bean
        RestClient mlRestClient(@Value("${wiremock.base-url}") String baseUrl) {
            return RestClient.builder().baseUrl(baseUrl).requestFactory(new SimpleClientHttpRequestFactory()).build();
        }

        @Bean
        @SuppressWarnings("unchecked")
        JobCompletionHandler autoCompletingHandler() {
            return new JobCompletionHandler() {
                @Override
                public void register(UUID jobId, DurableFuture<JobEvent> future) {
                    var event = new JobEvent(jobId, JobEventType.JOB_SUCCESS, Instant.now(),
                        1.0, "Pipeline completed successfully", Map.of(), null, null, Map.of());
                    ((CompletableDurableFuture<JobEvent>) future).complete(event);
                }

                @Override
                public void onJobSuccess(JobEvent e) {
                }

                @Override
                public void onJobFailure(JobEvent e) {
                }

                @Override
                public void onJobProgress(JobEvent e) {
                }
            };
        }

        @Bean
        MlTrainingService mlTrainingService(RestClient r, JobCompletionHandler h) {
            return new MlTrainingService(r, h);
        }

        @Bean
        PipelineSpecConverter pipelineSpecConverter() {
            return new PipelineSpecConverter(null, null) {
                @Override
                public CausalVerificationJobRequest convert(
                    PipelineSpecRequest spec, String reason, ModelSpace ms, String schema) {
                    return CausalVerificationJobRequest.builder()
                        .hypothesisId(spec.hypothesisId()).treatment(spec.treatment())
                        .outcome(spec.outcome()).treatmentForm(spec.treatmentForm())
                        .dagEdges("T -> Y").dsepThreshold(0.03).adjustmentSet(List.of("W1"))
                        .estimationVariants(List.of(new com.rorm.ml.dto.pipelinespec.EstimationVariant(
                            "primary", "T",
                            com.rorm.ml.dto.pipelinespec.TreatmentForm.CATEGORICAL,
                            "LinearDML", List.of("W1"), null, null, null, null)))
                        .gates(new com.rorm.ml.dto.pipelinespec.QualityGates(
                            new com.rorm.ml.dto.pipelinespec.QualityGates.NuisanceR2Gates(
                                0.01, 0.05, 0.01, 0.05, 0.5),
                            new com.rorm.ml.dto.pipelinespec.QualityGates.SanityGates(1, 0.5, 0.1),
                            new com.rorm.ml.dto.pipelinespec.QualityGates.PlaceboGates(0.3)))
                        .sensitivity(new com.rorm.ml.dto.pipelinespec.SensitivityConfig(
                            List.of(), List.of(), null, List.of()))
                        .residualChecks(new com.rorm.ml.dto.pipelinespec.ResidualChecks(
                            List.of(),
                            new com.rorm.ml.dto.pipelinespec.ResidualChecks.FieldCorrelationCheck(0.05, List.of()),
                            new com.rorm.ml.dto.pipelinespec.ResidualChecks.AutoCorrectionConfig(1, 0.05),
                            List.of()))
                        .rangeChecks(new com.rorm.ml.dto.pipelinespec.RangeChecks(
                            new com.rorm.ml.dto.pipelinespec.RangeChecks.VifConfig(10.0, List.of()),
                            List.of(),
                            List.of()))
                        .build();
                }
            };
        }

        @Bean
        DurableSwarmConfig config() {
            return SwarmTestFixtures.testConfig();
        }

        @Bean
        DurableSwarm durableSwarm(AiChatService chat, DurableSwarmConfig cfg,
                                  PipelineSpecConverter conv, MlTrainingService ml,
                                  ObjectMapper om, PromptPlaceholders pp) {
            return new DurableSwarm(chat, cfg, conv, ml, om, pp);
        }
    }
}
