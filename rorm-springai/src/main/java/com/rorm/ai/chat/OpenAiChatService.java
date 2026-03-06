package com.rorm.ai.chat;

import com.rorm.ai.RormToolContext;
import lombok.SneakyThrows;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Objects;
import java.util.UUID;

@Component
class OpenAiChatService implements AiChatService {

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final ChatRequestPreprocessor preprocessor;
    private final ToolGroupResolver toolGroupResolver;

    public OpenAiChatService(
        OpenAiChatModel chatModel,
        ChatMemory chatMemory,
        ChatRequestPreprocessor preprocessor,
        ToolGroupResolver toolGroupResolver
    ) {
        this.chatMemory = chatMemory;
        this.preprocessor = preprocessor;
        this.toolGroupResolver = toolGroupResolver;
        this.chatClient = ChatClient.builder(chatModel).build();
    }

    @SneakyThrows
    @Retryable(backoff = @Backoff(delay = 0))
    @Override
    public <T> T call(ChatRequest<T> request) {
        var clientRequest = buildSpec(request);
        if (request.responseType() == String.class) {
            @SuppressWarnings("unchecked")
            var result = (T) clientRequest.call().content();
            return result;
        }
        return clientRequest.call().entity(request.responseType());
    }

    private ChatClientRequestSpec buildSpec(ChatRequest<?> request) {
        var systemPrompt = preprocessor.resolveSystemPrompt(request);
        var context = new RormToolContext(request.modelSpace(), request.schema());

        var contextMap = new HashMap<>(context.toMap());
        contextMap.putAll(request.toolContextEntries());

        var tools = new ArrayList<>(toolGroupResolver.resolve(request.toolGroups()));
        tools.addAll(request.additionalTools());

        var clientRequest = chatClient.prompt()
            .system(systemPrompt)
            .toolContext(contextMap)
            .advisors(request.additionalAdvisors())
            .tools(tools.toArray(new Object[0]))
            .user(request.userPrompt())
            .advisors(MessageChatMemoryAdvisor.builder(chatMemory)
                .conversationId(Objects.requireNonNullElseGet(request.chatId(), () -> UUID.randomUUID().toString()))
                .build());

        applyOptions(clientRequest, request);
        return clientRequest;
    }

    private void applyOptions(ChatClientRequestSpec clientRequest, ChatRequest<?> request) {
        var level = request.thinkingLevel();
        var optsBuilder = OpenAiChatOptions.builder();
        if (level.requiresOptions()) {
            optsBuilder.reasoningEffort(level.apiValue());
        }
        if (request.modelName() != null) {
            optsBuilder.model(request.modelName());
        }
        clientRequest.options(optsBuilder.build());
    }

    @Override
    public Flux<String> stream(ChatRequest<?> request) {
        return buildSpec(request).stream().content();
    }
}
