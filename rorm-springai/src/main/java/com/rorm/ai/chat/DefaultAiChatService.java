package com.rorm.ai.chat;

import com.rorm.DurableRuntime;
import com.rorm.JobSpec;
import com.rorm.StepJournal;
import com.rorm.ai.anthropic.AnthropicChatOptions;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.UUID;

import static org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID;

@Component
@RequiredArgsConstructor
public class DefaultAiChatService implements AiChatService {

    private static final String BEAN_NAME = "defaultAiChatService";

    private final ChatClient chatClient;
    private final ChatRequestPreprocessor preprocessor;
    private final DurableRuntime durableRuntime;

    @Override
    @SuppressWarnings("unchecked")
    public <T> T call(ChatRequest<T> request) {
        if (request.sessionId() != null) {
            return (T) durableRuntime.submit(request.sessionId(),
                new JobSpec(BEAN_NAME, "doCall", new Object[]{request}));
        }
        return doCall(request);
    }

    @SuppressWarnings("unchecked")
    public <T> T doCall(ChatRequest<T> request) {
        var response = buildSpec(request).call();
        if (request.responseType() == String.class) {
            return (T) response.content();
        }
        return response.entity(request.responseType());
    }

    private ChatClient.ChatClientRequestSpec buildSpec(ChatRequest<?> request) {
        var callbacks = preprocessor.resolveToolCallbacks(request);
        var toolContext = preprocessor.buildToolContext(request);
        var chatId = request.chatId() != null ? request.chatId() : UUID.randomUUID().toString();

        var options = AnthropicChatOptions.builder()
            .model(request.modelName())
            .thinkingLevel(request.thinkingLevel())
            .webAccess(request.toolGroups().contains(ToolGroup.WEB_ACCESS))
            .toolCallbacks(callbacks)
            .toolContext(toolContext.getContext())
            .journal(StepJournal.current())
            .build();

        var spec = chatClient.prompt()
            .system(preprocessor.resolveSystemPrompt(request))
            .user(preprocessor.resolveUserPrompt(request))
            .options(options)
            .advisors(a -> a.param(CONVERSATION_ID, chatId));

        if (!request.additionalAdvisors().isEmpty()) {
            spec.advisors(request.additionalAdvisors().toArray(Advisor[]::new));
        }

        return spec;
    }

    @Override
    public Flux<String> stream(ChatRequest<?> request) {
        return buildSpec(request).stream().chatResponse()
            .mapNotNull(r -> r.getResult().getOutput().getText())
            .filter(t -> t != null && !t.isEmpty());
    }
}
