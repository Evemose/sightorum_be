package com.rorm.ai.chat;

import com.rorm.ai.MetamodelContextBuilder;
import com.rorm.ai.RormAiProperties;
import com.rorm.ai.RormToolContext;
import com.rorm.ai.prompt.AgentPromptBuilder;
import com.rorm.ai.tools.DataOverviewTool;
import com.rorm.ai.tools.QueryExecutionTool;
import com.rorm.ml.tools.MlTrainingTool;
import lombok.Getter;
import lombok.SneakyThrows;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

/**
 * Main service for AI-powered interactions.
 * Combines the chat model with function calling capabilities.
 */
@Getter
public class AiChatService {

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final AgentPromptBuilder promptBuilder;
    private final RormAiProperties properties;
    private final List<Advisor> defaultAdvisors;
    private final List<Object> defaultTools;

    public AiChatService(
        ChatModel chatModel,
        QueryExecutionTool queryExecutionTool,
        DataOverviewTool dataOverviewTool,
        MlTrainingTool mlTrainingTool,
        ChatMemory chatMemory,
        RormAiProperties properties
    ) {
        this.promptBuilder = new AgentPromptBuilder(new MetamodelContextBuilder());
        this.chatMemory = chatMemory;
        this.properties = properties;
        this.defaultTools = List.of(queryExecutionTool, dataOverviewTool, mlTrainingTool);
        this.defaultAdvisors = List.of(MessageChatMemoryAdvisor.builder(chatMemory).build());
        this.chatClient = ChatClient.builder(chatModel)
            .defaultTools(defaultTools.toArray(new Object[0]))
            .defaultAdvisors(defaultAdvisors)
            .build();
    }

    @SneakyThrows
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
        var modelSpace = request.modelSpace();
        var context = new RormToolContext(modelSpace, request.schema());
        var systemPrompt = request.systemPrompt() != null
            ? request.systemPrompt()
            : promptBuilder.buildSystemMessage(modelSpace);
        var userMessage = request.userPrompt();
        var advisors = new ArrayList<>(defaultAdvisors);
        advisors.addAll(request.additionalAdvisors());
        var tools = new ArrayList<>(defaultTools);
        tools.addAll(request.additionalTools());

        var clientRequest = chatClient.prompt()
            .system(systemPrompt)
            .toolContext(context.toMap())
            .advisors(advisors)
            .tools(tools.toArray(new Object[0]))
            .user(userMessage);

        if (request.chatId() != null) {
            clientRequest.advisors(MessageChatMemoryAdvisor.builder(chatMemory)
                .conversationId(request.chatId())
                .build());
        }

        applyOptions(clientRequest, request);
        return clientRequest;
    }

    private void applyOptions(ChatClient.ChatClientRequestSpec clientRequest, ChatRequest<?> request) {
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

    public Flux<String> stream(ChatRequest<?> request) {
        return buildSpec(request).stream().content();
    }
}
