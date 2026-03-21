package com.rorm.ai.chat;

import com.rorm.DurableRuntime;
import com.rorm.JobSpec;
import com.rorm.StepJournal;
import com.rorm.ai.anthropic.AnthropicChatOptions;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
@RequiredArgsConstructor
public class DefaultAiChatService implements AiChatService {

    private static final String BEAN_NAME = "defaultAiChatService";
    private static final ConcurrentHashMap<String, ChatRequest<?>> PENDING_REQUESTS = new ConcurrentHashMap<>();

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final ChatRequestPreprocessor preprocessor;
    private final DurableRuntime durableRuntime;

    @Override
    @SuppressWarnings("unchecked")
    public <T> T call(ChatRequest<T> request) {
        if (request.sessionId() != null) {
            var requestId = UUID.randomUUID().toString();
            PENDING_REQUESTS.put(requestId, request);
            return (T) durableRuntime.submit(request.sessionId(),
                new JobSpec(BEAN_NAME, "doDurableCall", new Object[]{requestId}));
        }
        return doCall(request);
    }

    @SuppressWarnings("unchecked")
    private <T> T doCall(ChatRequest<T> request) {
        var response = buildSpec(request).call();
        if (request.responseType() == String.class) {
            return (T) response.content();
        }
        return response.entity(request.responseType());
    }

    private ChatClient.ChatClientRequestSpec buildSpec(ChatRequest<?> request) {
        var callbacks = preprocessor.resolveToolCallbacks(request);
        var toolContext = preprocessor.buildToolContext(request);

        var options = AnthropicChatOptions.builder()
            .model(request.modelName())
            .thinkingLevel(request.thinkingLevel())
            .webAccess(request.toolGroups().contains(ToolGroup.WEB_ACCESS))
            .toolCallbacks(callbacks)
            .toolContext(toolContext.getContext())
            .journal(StepJournal.current())
            .cachingStrategyFunction(request.cachingStrategyFunction())
            .build();

        var advisors = buildAdvisors(request);
        return chatClient.prompt()
            .system(preprocessor.resolveSystemPrompt(request))
            .user(preprocessor.resolveUserPrompt(request))
            .options(options)
            .advisors(advisors);
    }

    private List<Advisor> buildAdvisors(ChatRequest<?> request) {
        var baseAdvisors = new ArrayList<>(request.additionalAdvisors());
        if (baseAdvisors.stream().noneMatch(MessageChatMemoryAdvisor.class::isInstance)) {
            baseAdvisors.add(MessageChatMemoryAdvisor.builder(chatMemory)
                .conversationId(request.chatId() != null ? request.chatId() : UUID.randomUUID().toString())
                .build());
        }
        return baseAdvisors;
    }

    @SuppressWarnings("unused")
    public Object doDurableCall(String requestId) {
        var request = PENDING_REQUESTS.get(requestId);
        if (request == null) {
            throw new IllegalStateException("No pending request found for id: " + requestId);
        }
        return doCall(request);
    }

    @Override
    public Flux<String> stream(ChatRequest<?> request) {
        return buildSpec(request).stream().chatResponse()
            .mapNotNull(r -> r.getResult().getOutput().getText())
            .filter(t -> t != null && !t.isEmpty());
    }
}
