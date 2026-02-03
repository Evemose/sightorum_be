package com.rorm.ai;

import com.rorm.ai.chat.ChatProgress;
import com.rorm.ai.chat.ChatProgressRepository;
import com.rorm.ai.tools.DataOverviewTool;
import com.rorm.ai.tools.QueryExecutionTool;
import com.rorm.metamodel.ModelSpace;
import com.rorm.ml.tools.MlTrainingTool;
import lombok.Getter;
import lombok.SneakyThrows;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import reactor.core.publisher.Flux;

/**
 * Main service for AI-powered database queries.
 * Combines the chat model with function calling capabilities to allow
 * natural language queries against the database schema.
 * Uses the RORM Query model directly via Jackson serialization with
 * comprehensive property descriptions from Jackson mixins.
 */
@Getter
public class RormAiService {

    private final ChatClient chatClient;
    private final MetamodelContextBuilder contextBuilder;
    private final ChatMemory chatMemory;
    private final ChatProgressRepository chatProgressRepository;
    private final RormAiProperties properties;

    public RormAiService(
        ChatModel chatModel,
        QueryExecutionTool queryExecutionTool,
        DataOverviewTool dataOverviewTool,
        MlTrainingTool mlTrainingTool,
        ChatMemory chatMemory,
        ChatProgressRepository chatProgressRepository,
        RormAiProperties properties
    ) {
        this.contextBuilder = new MetamodelContextBuilder();
        this.chatMemory = chatMemory;
        this.chatProgressRepository = chatProgressRepository;
        this.properties = properties;
        this.chatClient = ChatClient.builder(chatModel)
            .defaultTools(queryExecutionTool, dataOverviewTool, mlTrainingTool)
            .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
            .build();
    }

    /**
     * Ask a question in natural language and get an AI-generated response.
     * The AI will analyze the question, execute appropriate queries, and
     * format the results in a human-readable way.
     *
     * @param modelSpace The model space containing the database schema
     * @param prompt The natural language question to answer
     * @return AI-generated response with query results
     */
    @SneakyThrows
    public String ask(ModelSpace modelSpace, String prompt) {
        var progress = chatProgressRepository.save(new ChatProgress(modelSpace));
        var context = new RormToolContext(progress);
        var systemPrompt = buildSystemPrompt(modelSpace);

        return chatClient.prompt()
            .system(systemPrompt)
            .toolContext(context.toMap())
            .user(prompt)
            .advisors(
                MessageChatMemoryAdvisor.builder(chatMemory)
                    .conversationId(progress.getConversationId().toString())
                    .build()
            ).call()
            .content();
    }

    private String buildSystemPrompt(ModelSpace modelSpace) {
        var schemaContext = contextBuilder.buildContext(modelSpace);
        return properties.systemPrompt() + "\n\n" + schemaContext;
    }

    @SneakyThrows
    public void proceed(ChatProgress chatProgress, String prompt) {
        var context = new RormToolContext(chatProgress);
        var systemPrompt = buildSystemPrompt(chatProgress.getModelSpace());

        chatClient.prompt()
            .system(systemPrompt)
            .toolContext(context.toMap())
            .user(prompt)
            .advisors(
                MessageChatMemoryAdvisor.builder(chatMemory)
                    .conversationId(chatProgress.getConversationId().toString())
                    .build()
            ).call();
    }

    /**
     * Ask a question with streaming response.
     * Returns a Flux that emits tokens as they are generated.
     *
     * @param chatProgress The chat progress context
     * @param prompt       The natural language question
     * @return Flux of response tokens
     */
    public Flux<String> askStreaming(ChatProgress chatProgress, String prompt) {
        var context = new RormToolContext(chatProgress);
        var systemPrompt = buildSystemPrompt(chatProgress.getModelSpace());

        return chatClient.prompt()
            .system(systemPrompt)
            .toolContext(context.toMap())
            .user(prompt)
            .advisors(
                MessageChatMemoryAdvisor.builder(chatMemory)
                    .conversationId(chatProgress.getConversationId().toString())
                    .build()
            )
            .stream()
            .content();
    }

    /**
     * Continue a conversation with streaming response.
     *
     * @param chatProgress The chat progress context
     * @param prompt       The user's message
     * @return Flux of response tokens
     */
    public Flux<String> proceedStreaming(ChatProgress chatProgress, String prompt) {
        var context = new RormToolContext(chatProgress);
        var systemPrompt = buildSystemPrompt(chatProgress.getModelSpace());

        return chatClient.prompt()
            .system(systemPrompt)
            .toolContext(context.toMap())
            .user(prompt)
            .advisors(
                MessageChatMemoryAdvisor.builder(chatMemory)
                    .conversationId(chatProgress.getConversationId().toString())
                    .build()
            )
            .stream()
            .content();
    }
}
