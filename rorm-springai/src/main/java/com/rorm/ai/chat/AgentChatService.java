package com.rorm.ai.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rorm.StepJournal;
import com.rorm.ai.anthropic.AnthropicChatOptions;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.retry.support.RetrySynchronizationManager;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

@Component(AgentChatService.BEAN_NAME)
@RequiredArgsConstructor
public class AgentChatService implements AiChatService {

    static final String BEAN_NAME = "defaultAiChatService";

    private final ChatClient chatClient;
    private final ChatMemoryManager memoryManager;
    private final ChatRequestPreprocessor preprocessor;
    private final ObjectMapper objectMapper;

    @Override
    @SuppressWarnings("unchecked")
    @Retryable(retryFor = {JsonProcessingException.class}, backoff = @Backoff(delay = 1), maxAttempts = 10)
    public <T> T call(ChatRequest<T> request) {
        var response = buildSpec(request).call();
        if (request.responseType() == String.class) {
            return (T) response.content();
        }
        return response.entity(new BeanOutputConverter<>(request.responseType(), objectMapper));
    }

    private ChatClient.ChatClientRequestSpec buildSpec(ChatRequest<?> request) {
        var callbacks = preprocessor.resolveToolCallbacks(request);
        var toolContext = preprocessor.buildToolContext(request);
        var ctx = RetrySynchronizationManager.getContext();

        var options = AnthropicChatOptions.builder()
            .model(request.modelName())
            .thinkingLevel(request.thinkingLevel())
            .webAccess(request.toolGroups().contains(ToolGroup.WEB_ACCESS))
            .toolCallbacks(callbacks)
            .toolContext(toolContext.getContext())
            .journal(StepJournal.current())
            .cachingStrategyFunction(request.cachingStrategyFunction())
            .responseSchema(request.responseSchema())
            .build();

        var advisors = buildAdvisors(request);
        var system = preprocessor.resolveSystemPrompt(request);
        if (ctx != null && ctx.getLastThrowable() != null) {
            system += "\n\n" + "Last error: " + ctx.getLastThrowable();
        }
        return chatClient.prompt()
            .system(system)
            .user(preprocessor.resolveUserPrompt(request))
            .options(options)
            .advisors(advisors);
    }

    private List<Advisor> buildAdvisors(ChatRequest<?> request) {
        var advisors = new ArrayList<>(request.additionalAdvisors());
        if (advisors.stream().noneMatch(TypedChatMemoryAdvisor.class::isInstance)) {
            advisors.add(
                TypedChatMemoryAdvisor.builder()
                    .memoryManager(memoryManager)
                    .conversationId(request.chatId() != null ?
                        request.chatId() :
                        StepJournal.current().randomUUID().toString())
                    .includes(request.memoryIncludes())
                    .build()
            );
        }
        return advisors;
    }

    @Override
    public Flux<String> stream(ChatRequest<?> request) {
        return streamTokens(request)
            .map(StreamToken::toText)
            .filter(t -> !t.isEmpty());
    }

    @Override
    public Flux<StreamToken> streamTokens(ChatRequest<?> request) {
        return streamChatResponses(request).mapNotNull(StreamToken::from);
    }

    @Override
    public Flux<ChatResponse> streamChatResponses(ChatRequest<?> request) {
        return buildSpec(request).stream().chatResponse();
    }
}
