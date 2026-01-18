package com.rorm.ai;

import com.rorm.ai.tools.DataOverviewTool;
import com.rorm.ai.tools.QueryExecutionTool;
import com.rorm.metamodel.ModelSpace;
import lombok.Getter;
import lombok.SneakyThrows;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;

import java.util.ArrayList;
import java.util.List;

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
    private final String systemPrompt;

    @SneakyThrows
    public RormAiService(
        ChatModel chatModel,
        ModelSpace modelSpace,
        RormAiProperties properties,
        QueryExecutionTool queryExecutionTool,
        DataOverviewTool dataOverviewTool
    ) {
        var contextBuilder = new MetamodelContextBuilder(properties.includeLocationDetails());
        var schemaContext = contextBuilder.buildContext(modelSpace);

        this.systemPrompt = properties.systemPrompt() + "\n\n" + schemaContext;

        this.chatClient = ChatClient.builder(chatModel)
            .defaultTools(queryExecutionTool, dataOverviewTool)
            .build();
    }

    /**
     * Ask a question in natural language and get an AI-generated response.
     * The AI will analyze the question, execute appropriate queries, and
     * format the results in a human-readable way.
     *
     * @param userQuestion The natural language question to answer
     * @return AI-generated response with query results
     */
    public String ask(String userQuestion) {
        return chatClient.prompt()
            .system(systemPrompt)
            .user(userQuestion)
            .call()
            .content();
    }

    /**
     * Have a multi-turn conversation with context about the database.
     *
     * @param conversationHistory Previous messages in the conversation
     * @param newQuestion         The new question to ask
     * @return AI-generated response
     */
    public String chat(List<Message> conversationHistory, String newQuestion) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt));
        messages.addAll(conversationHistory);
        messages.add(new UserMessage(newQuestion));

        return chatClient.prompt()
            .messages(messages)
            .call()
            .content();
    }

}
