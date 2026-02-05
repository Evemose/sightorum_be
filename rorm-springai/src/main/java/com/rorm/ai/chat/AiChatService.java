package com.rorm.ai.chat;

import com.rorm.ai.MetamodelContextBuilder;
import com.rorm.ai.RormAiProperties;
import com.rorm.ai.RormToolContext;
import com.rorm.ai.prompt.AgentPromptBuilder;
import com.rorm.ai.tools.ChatHistoryTool;
import com.rorm.ai.tools.DataOverviewTool;
import com.rorm.ai.tools.QueryExecutionTool;
import com.rorm.ml.tools.MlTrainingTool;
import lombok.Getter;
import lombok.SneakyThrows;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;

/**
 * Main service for AI-powered database queries.
 * Combines the chat model with function calling capabilities to allow
 * natural language queries against the database schema.
 * Uses the RORM Query model directly via Jackson serialization with
 * comprehensive property descriptions from Jackson mixins.
 *
 * <p>Prompt structure follows best practices:
 * <ul>
 *   <li>System message = static rules, schema reference, and tool documentation (cacheable)</li>
 *   <li>User message = dynamic context and current task</li>
 * </ul>
 */
@Getter
public class AiChatService {

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final AgentPromptBuilder promptBuilder;
    private final RormAiProperties properties;

    public AiChatService(
        ChatModel chatModel,
        QueryExecutionTool queryExecutionTool,
        DataOverviewTool dataOverviewTool,
        MlTrainingTool mlTrainingTool,
        ChatHistoryTool chatHistoryTool,
        ChatMemory chatMemory,
        RormAiProperties properties
    ) {
        this.promptBuilder = new AgentPromptBuilder(new MetamodelContextBuilder());
        this.chatMemory = chatMemory;
        this.properties = properties;
        this.chatClient = ChatClient.builder(chatModel)
            .defaultTools(queryExecutionTool, dataOverviewTool, mlTrainingTool, chatHistoryTool)
            .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
            .build();
    }

    /**
     * Execute a chat request and get a blocking response.
     *
     * @param request The chat request containing progress, prompt, response type and optional chat ID
     * @return AI-generated response of the requested type
     */
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
        var progress = request.progress();
        var context = new RormToolContext(progress, request.schema());
        var systemPrompt = promptBuilder.buildSystemMessage(progress.getModelSpace());
        var userMessage = promptBuilder.buildContextMessage(progress, request.prompt());

        var clientRequest = chatClient.prompt()
            .system(systemPrompt)
            .toolContext(context.toMap())
            .user(userMessage);

        if (request.chatId() != null) {
            clientRequest.advisors(MessageChatMemoryAdvisor.builder(chatMemory)
                .conversationId(request.chatId())
                .build());
        }

        applyThinkingLevel(clientRequest, request.thinkingLevel());
        return clientRequest;
    }

    private void applyThinkingLevel(ChatClient.ChatClientRequestSpec clientRequest, ThinkingLevel level) {
        if (level.requiresOptions()) {
            clientRequest.options(OpenAiChatOptions.builder()
                .reasoningEffort(level.apiValue())
                .build());
        }
    }

    /**
     * Execute a chat request with streaming response.
     *
     * @param request The chat request containing progress, prompt and optional chat ID
     * @return Flux of response tokens
     */
    public Flux<String> stream(ChatRequest<?> request) {
        return buildSpec(request).stream().content();
    }
}
