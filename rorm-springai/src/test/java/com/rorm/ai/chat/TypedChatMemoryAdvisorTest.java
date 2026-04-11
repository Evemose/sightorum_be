package com.rorm.ai.chat;

import com.rorm.StepJournal;
import com.rorm.ai.anthropic.ServerToolGeneration;
import com.rorm.ai.anthropic.ThinkingGeneration;
import com.rorm.ai.anthropic.ToolRoundGeneration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class TypedChatMemoryAdvisorTest {

    private static final String CONV_ID = "test-conv";
    private RecordingMemoryRepository repository;
    private ChatMemoryManager memoryManager;

    private static CallAdvisorChain callChain(Function<ChatClientRequest, ChatClientResponse> fn) {
        return new CallAdvisorChain() {
            @Override
            public ChatClientResponse nextCall(ChatClientRequest req) {
                return fn.apply(req);
            }

            @Override
            public List<CallAdvisor> getCallAdvisors() {
                return List.of();
            }

            @Override
            public CallAdvisorChain copy(CallAdvisor after) {
                return this;
            }
        };
    }

    private static StreamAdvisorChain streamChain(Function<ChatClientRequest, Flux<ChatClientResponse>> fn) {
        return new StreamAdvisorChain() {
            @Override
            public Flux<ChatClientResponse> nextStream(ChatClientRequest req) {
                return fn.apply(req);
            }

            @Override
            public List<StreamAdvisor> getStreamAdvisors() {
                return List.of();
            }
        };
    }

    private static ChatClientResponse wrap(ChatResponse cr) {
        return ChatClientResponse.builder().chatResponse(cr).build();
    }

    @BeforeEach
    void setUp() {
        repository = new RecordingMemoryRepository();
        memoryManager = new ChatMemoryManager(repository);
    }

    private TypedChatMemoryAdvisor advisor(Set<MemoryInclude> includes) {
        return TypedChatMemoryAdvisor.builder()
            .memoryManager(memoryManager)
            .conversationId(CONV_ID)
            .includes(includes)
            .journal(StepJournal.DEFAULT)
            .build();
    }

    // -- Custom model (with custom Generation subtypes) --

    private static class RecordingMemoryRepository implements org.springframework.ai.chat.memory.ChatMemoryRepository {
        private final Map<String, List<Message>> store = new java.util.concurrent.ConcurrentHashMap<>();

        @Override
        public List<String> findConversationIds() {
            return List.copyOf(store.keySet());
        }

        @Override
        public List<Message> findByConversationId(String conversationId) {
            return new ArrayList<>(store.getOrDefault(conversationId, List.of()));
        }

        @Override
        public void saveAll(String conversationId, List<Message> messages) {
            store.put(conversationId, new ArrayList<>(messages));
        }

        @Override
        public void deleteByConversationId(String conversationId) {
            store.remove(conversationId);
        }
    }

    // -- Plain model (no custom generations — graceful degradation) --

    @Nested
    class CustomModelAware {

        @Test
        void callSavesThinkingAndToolRoundsAndServerTools() {
            var toolCallAssistant = AssistantMessage.builder()
                .content("I need to call a tool")
                .toolCalls(List.of(new AssistantMessage.ToolCall("tc1", "function", "myTool", "{}")))
                .build();
            var toolResponse = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("tc1", "myTool", "result")))
                .build();
            var finalAssistant = AssistantMessage.builder().content("Final answer").build();
            var serverTool = new ServerToolMessage("web_search", "{\"query\":\"test\"}", "{\"results\":[]}");

            var chatResponse = new ChatResponse(List.of(
                new Generation(finalAssistant),
                new ThinkingGeneration("Let me think about this..."),
                new ToolRoundGeneration(toolCallAssistant, toolResponse),
                new ServerToolGeneration(serverTool)
            ), ChatResponseMetadata.builder().build());

            var request = ChatClientRequest.builder().prompt(new Prompt("What is 2+2?")).build();
            advisor(Set.of()).adviseCall(request, callChain(_ -> wrap(chatResponse)));

            var saved = repository.findByConversationId(CONV_ID);
            assertThat(saved).hasSize(6);
            assertThat(saved.get(0)).isInstanceOf(UserMessage.class);
            // generation 0: final assistant text
            assertThat(saved.get(1)).isInstanceOf(AssistantMessage.class);
            assertThat(((AssistantMessage) saved.get(1)).getText()).isEqualTo("Final answer");
            // custom generations
            assertThat(saved.get(2)).isInstanceOf(ThinkingMessage.class);
            assertThat(((ThinkingMessage) saved.get(2)).text()).isEqualTo("Let me think about this...");
            assertThat(saved.get(3)).isInstanceOf(AssistantMessage.class);
            assertThat(((AssistantMessage) saved.get(3)).getToolCalls()).hasSize(1);
            assertThat(saved.get(4)).isInstanceOf(ToolResponseMessage.class);
            assertThat(saved.get(5)).isInstanceOf(ServerToolMessage.class);
        }

        @Test
        void streamCollectsCustomGenerationsBeforeAggregation() {
            var toolCallAssistant = AssistantMessage.builder()
                .content("calling tool")
                .toolCalls(List.of(new AssistantMessage.ToolCall("tc1", "function", "myTool", "{}")))
                .build();
            var toolResponse = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("tc1", "myTool", "done")))
                .build();

            var textChunk1 = new ChatResponse(List.of(new Generation(
                AssistantMessage.builder().content("Hello ").build())));
            var textChunk2 = new ChatResponse(List.of(new Generation(
                AssistantMessage.builder().content("world").build())));
            var customGens = new ChatResponse(List.of(
                new Generation(AssistantMessage.builder().content("").build()),
                new ThinkingGeneration("deep thought"),
                new ToolRoundGeneration(toolCallAssistant, toolResponse)
            ));

            var request = ChatClientRequest.builder().prompt(new Prompt("Hi")).build();
            var chain = streamChain(_ -> Flux.just(textChunk1, textChunk2, customGens).map(TypedChatMemoryAdvisorTest::wrap));

            StepVerifier.create(advisor(Set.of()).adviseStream(request, chain))
                .thenConsumeWhile(_ -> true)
                .verifyComplete();

            var saved = repository.findByConversationId(CONV_ID);
            assertThat(saved).anyMatch(ThinkingMessage.class::isInstance);
            assertThat(saved).anyMatch(m -> m instanceof AssistantMessage am && !am.getToolCalls().isEmpty());
            assertThat(saved).anyMatch(ToolResponseMessage.class::isInstance);
        }
    }

    // -- History loading with granularity filtering --

    @Nested
    class PlainModelFallback {

        @Test
        void callSavesOnlyFinalAssistantForPlainModel() {
            var chatResponse = new ChatResponse(
                List.of(new Generation(AssistantMessage.builder().content("Plain answer").build())),
                ChatResponseMetadata.builder().build()
            );

            var request = ChatClientRequest.builder().prompt(new Prompt("Question?")).build();
            advisor(Set.of()).adviseCall(request, callChain(_ -> wrap(chatResponse)));

            var saved = repository.findByConversationId(CONV_ID);
            assertThat(saved).hasSize(2);
            assertThat(saved.get(0)).isInstanceOf(UserMessage.class);
            assertThat(saved.get(1)).isInstanceOf(AssistantMessage.class);
            assertThat(((AssistantMessage) saved.get(1)).getText()).isEqualTo("Plain answer");
        }

        @Test
        void streamSavesOnlyAggregatedTextForPlainModel() {
            var chunk1 = new ChatResponse(List.of(new Generation(
                AssistantMessage.builder().content("Part 1 ").build())));
            var chunk2 = new ChatResponse(List.of(new Generation(
                AssistantMessage.builder().content("Part 2").build())));

            var request = ChatClientRequest.builder().prompt(new Prompt("Stream question")).build();
            var chain = streamChain(_ -> Flux.just(chunk1, chunk2).map(TypedChatMemoryAdvisorTest::wrap));

            StepVerifier.create(advisor(Set.of()).adviseStream(request, chain))
                .thenConsumeWhile(_ -> true)
                .verifyComplete();

            var saved = repository.findByConversationId(CONV_ID);
            assertThat(saved).hasSize(2);
            assertThat(saved.get(0)).isInstanceOf(UserMessage.class);
            assertThat(saved.get(1)).isInstanceOf(AssistantMessage.class);
            assertThat(((AssistantMessage) saved.get(1)).getText()).isEqualTo("Part 1 Part 2");
        }
    }

    @Nested
    class HistoryFiltering {

        @Test
        void filtersThinkingWhenNotIncluded() {
            repository.saveAll(CONV_ID, List.of(
                new UserMessage("Hi"),
                new ThinkingMessage("internal thought"),
                AssistantMessage.builder().content("Hello").build()
            ));

            var chatResponse = new ChatResponse(
                List.of(new Generation(AssistantMessage.builder().content("Follow-up").build())),
                ChatResponseMetadata.builder().build()
            );
            var request = ChatClientRequest.builder().prompt(new Prompt("Next")).build();

            advisor(Set.of()).adviseCall(request, callChain(req -> {
                assertThat(req.prompt().getInstructions()).noneMatch(ThinkingMessage.class::isInstance);
                assertThat(req.prompt().getInstructions()).hasSize(3);
                return wrap(chatResponse);
            }));
        }

        @Test
        void includesThinkingWhenRequested() {
            repository.saveAll(CONV_ID, List.of(
                new UserMessage("Hi"),
                new ThinkingMessage("internal thought"),
                AssistantMessage.builder().content("Hello").build()
            ));

            var chatResponse = new ChatResponse(
                List.of(new Generation(AssistantMessage.builder().content("Follow-up").build())),
                ChatResponseMetadata.builder().build()
            );
            var request = ChatClientRequest.builder().prompt(new Prompt("Next")).build();

            advisor(Set.of(MemoryInclude.THINKING)).adviseCall(request, callChain(req -> {
                assertThat(req.prompt().getInstructions()).anyMatch(ThinkingMessage.class::isInstance);
                assertThat(req.prompt().getInstructions()).hasSize(4);
                return wrap(chatResponse);
            }));
        }

        @Test
        void stripsToolCallsFromAssistantWhenNotIncluded() {
            repository.saveAll(CONV_ID, List.of(
                AssistantMessage.builder()
                    .content("Using tool")
                    .toolCalls(List.of(new AssistantMessage.ToolCall("tc1", "function", "tool", "{}")))
                    .build()
            ));

            var chatResponse = new ChatResponse(
                List.of(new Generation(AssistantMessage.builder().content("ok").build())),
                ChatResponseMetadata.builder().build()
            );
            var request = ChatClientRequest.builder().prompt(new Prompt("Next")).build();

            advisor(Set.of()).adviseCall(request, callChain(req -> {
                var assistants = req.prompt().getInstructions().stream()
                    .filter(AssistantMessage.class::isInstance)
                    .map(AssistantMessage.class::cast)
                    .toList();
                assertThat(assistants).allMatch(am -> am.getToolCalls().isEmpty());
                return wrap(chatResponse);
            }));
        }

        @Test
        void preservesToolCallsWhenIncluded() {
            repository.saveAll(CONV_ID, List.of(
                AssistantMessage.builder()
                    .content("Using tool")
                    .toolCalls(List.of(new AssistantMessage.ToolCall("tc1", "function", "tool", "{}")))
                    .build()
            ));

            var chatResponse = new ChatResponse(
                List.of(new Generation(AssistantMessage.builder().content("ok").build())),
                ChatResponseMetadata.builder().build()
            );
            var request = ChatClientRequest.builder().prompt(new Prompt("Next")).build();

            advisor(Set.of(MemoryInclude.TOOL_CALLS)).adviseCall(request, callChain(req -> {
                var assistants = req.prompt().getInstructions().stream()
                    .filter(AssistantMessage.class::isInstance)
                    .map(AssistantMessage.class::cast)
                    .toList();
                assertThat(assistants).anyMatch(am -> !am.getToolCalls().isEmpty());
                return wrap(chatResponse);
            }));
        }
    }
}
