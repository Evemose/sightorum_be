package com.rorm.ai.chat;

import com.rorm.StepJournal;
import com.rorm.ai.anthropic.AnthropicChatOptions;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

@Component(AgentChatService.BEAN_NAME)
@RequiredArgsConstructor
public class AgentChatService implements AiChatService {

    static final String BEAN_NAME = "defaultAiChatService";

    private final ChatClient chatClient;
    private final ChatMemoryRepository chatMemoryRepository;
    private final ChatRequestPreprocessor preprocessor;

    @Override
    @SuppressWarnings("unchecked")
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
        var baseAdvisors = new ArrayList<>(request.additionalAdvisors());
        if (baseAdvisors.stream().noneMatch(MessageChatMemoryAdvisor.class::isInstance)) {
            baseAdvisors.add(
                MessageChatMemoryAdvisor.builder(new AgentChatMemory(chatMemoryRepository, StepJournal.current()))
                    .conversationId(request.chatId() != null ?
                        request.chatId() :
                        StepJournal.current().randomUUID().toString())
                    .build()
            );
        }
        return baseAdvisors;
    }

    @Override
    public Flux<String> stream(ChatRequest<?> request) {
        return buildSpec(request).stream().chatResponse()
            .mapNotNull(r -> r.getResult().getOutput().getText())
            .filter(t -> t != null && !t.isEmpty());
    }
}
