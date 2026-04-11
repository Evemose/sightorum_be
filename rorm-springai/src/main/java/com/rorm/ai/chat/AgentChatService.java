package com.rorm.ai.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.rorm.StepJournal;
import com.rorm.ai.anthropic.AnthropicChatOptions;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
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

    @Override
    @SuppressWarnings("unchecked")
    @Retryable(retryFor = {JsonProcessingException.class}, backoff = @Backoff(delay = 1))
    public <T> T call(ChatRequest<T> request) {
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
