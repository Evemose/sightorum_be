package com.rorm.ai.chat;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Chat service with per-round conversation persistence.
 * <p>
 * Each LLM call and tool execution is a "round." After each round, messages are
 * persisted to {@link ChatMemory}. If a tool fails, the exception propagates but
 * messages up to that point are saved. On resume with the same conversation ID,
 * messages are loaded and the conversation continues from where it left off.
 */
@Slf4j
@RequiredArgsConstructor
public class DurableChatService {

    private static final int MAX_TOOL_ROUNDS = 20;

    private final ChatModel chatModel;
    private final ChatMemory chatMemory;

    public ChatResponse chat(String conversationId, String userMessage,
                             List<Message> systemMessages, ChatOptions options,
                             Map<String, ToolCallback> toolCallbacks) {
        var history = new ArrayList<>(chatMemory.get(conversationId));
        var isResume = !history.isEmpty();

        if (isResume) {
            log.info("Resuming conversation {} with {} existing messages", conversationId, history.size());
            var lastMessage = history.getLast();
            if (lastMessage instanceof AssistantMessage am && am.hasToolCalls()) {
                return continueToolLoop(conversationId, history, options, toolCallbacks);
            }
        }

        var userMsg = new UserMessage(userMessage);
        history.add(userMsg);
        chatMemory.add(conversationId, userMsg);

        return runToolLoop(conversationId, history, systemMessages, options, toolCallbacks);
    }

    private ChatResponse runToolLoop(String conversationId, List<Message> history,
                                     List<Message> systemMessages, ChatOptions options,
                                     Map<String, ToolCallback> toolCallbacks) {
        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            var allMessages = new ArrayList<>(systemMessages);
            allMessages.addAll(history);
            var response = chatModel.call(new Prompt(allMessages, options));
            var assistant = response.getResult().getOutput();

            chatMemory.add(conversationId, assistant);
            history.add(assistant);

            if (!assistant.hasToolCalls()) {
                return response;
            }

            var toolResults = executeTools(conversationId, assistant, toolCallbacks);
            history.add(toolResults);
        }
        throw new IllegalStateException("Tool loop exceeded " + MAX_TOOL_ROUNDS + " rounds");
    }

    private ChatResponse continueToolLoop(String conversationId, List<Message> history,
                                          ChatOptions options,
                                          Map<String, ToolCallback> toolCallbacks) {
        var lastAssistant = (AssistantMessage) history.getLast();
        var toolResults = executeTools(conversationId, lastAssistant, toolCallbacks);
        history.add(toolResults);

        return runToolLoop(conversationId, history, List.of(), options, toolCallbacks);
    }

    private ToolResponseMessage executeTools(String conversationId, AssistantMessage assistant,
                                             Map<String, ToolCallback> toolCallbacks) {
        var responses = new ArrayList<ToolResponseMessage.ToolResponse>();
        for (var toolCall : assistant.getToolCalls()) {
            var callback = toolCallbacks.get(toolCall.name());
            if (callback == null) {
                throw new IllegalStateException("Unknown tool: " + toolCall.name());
            }
            log.info("Executing tool: {}", toolCall.name());
            var result = callback.call(toolCall.arguments());
            responses.add(new ToolResponseMessage.ToolResponse(toolCall.id(), toolCall.name(), result));
        }
        var toolResponse = ToolResponseMessage.builder().responses(responses).build();
        chatMemory.add(conversationId, toolResponse);
        return toolResponse;
    }
}
