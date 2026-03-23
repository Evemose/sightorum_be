package com.rorm.ai.chat;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.*;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Predicate;

@Component
@RequiredArgsConstructor
public class ChatConversationFormatter {

    private final ChatMemoryRepository chatMemoryRepository;

    public String toMarkdown(String conversationId) {
        return toMarkdown(conversationId, _ -> true);
    }

    public String toMarkdown(String conversationId, Predicate<Message> filter) {
        var messages = chatMemoryRepository.findByConversationId(conversationId).stream()
            .filter(filter)
            .toList();
        return toMarkdown(messages);
    }

    public String toMarkdown(List<Message> messages) {
        if (messages.isEmpty()) {
            return "";
        }
        var sb = new StringBuilder();
        boolean first = true;
        for (var message : messages) {
            if (!first) {
                sb.append("---\n\n");
            }
            first = false;
            renderMessage(sb, message);
        }
        return sb.toString().stripTrailing();
    }

    private static void renderMessage(StringBuilder sb, Message message) {
        switch (message) {
            case ThinkingMessage tm -> formatThinkingMessage(sb, tm);
            case ServerToolMessage stm -> formatServerToolMessage(sb, stm);
            case UserMessage um -> formatUserMessage(sb, um);
            case AssistantMessage am -> formatAssistantMessage(sb, am);
            case SystemMessage sm -> formatSystemMessage(sb, sm);
            case ToolResponseMessage trm -> formatToolResponseMessage(sb, trm);
            default -> throw new IllegalArgumentException(
                "Unknown message type: " + message.getClass().getName());
        }
    }

    private static void formatThinkingMessage(StringBuilder sb, ThinkingMessage tm) {
        sb.append("### Thinking\n\n");
        appendBlockquote(sb, tm.text());
    }

    private static void formatServerToolMessage(StringBuilder sb, ServerToolMessage stm) {
        sb.append("### Server Tool: ").append(stm.toolName()).append("\n\n");
        if (stm.inputJson() != null && !stm.inputJson().isBlank()) {
            sb.append("**Input:**\n```json\n").append(stm.inputJson()).append("\n```\n\n");
        }
        if (stm.outputJson() != null && !stm.outputJson().isBlank()) {
            sb.append("**Output:**\n```json\n").append(stm.outputJson()).append("\n```\n\n");
        }
    }

    private static void formatUserMessage(StringBuilder sb, UserMessage um) {
        sb.append("### User\n\n");
        sb.append(um.getText()).append("\n\n");
    }

    private static void formatAssistantMessage(StringBuilder sb, AssistantMessage am) {
        sb.append("### Assistant\n\n");
        var text = am.getText();
        if (text != null && !text.isBlank()) {
            sb.append(text).append("\n\n");
        }
        for (var tc : am.getToolCalls()) {
            sb.append("**Tool Call:** `").append(tc.name()).append("`\n\n");
            if (!tc.arguments().isBlank()) {
                sb.append("```json\n").append(tc.arguments()).append("\n```\n\n");
            }
        }
    }

    private static void formatSystemMessage(StringBuilder sb, SystemMessage sm) {
        sb.append("### System\n\n");
        sb.append(sm.getText()).append("\n\n");
    }

    private static void formatToolResponseMessage(StringBuilder sb, ToolResponseMessage trm) {
        sb.append("### Tool Result\n\n");
        for (var tr : trm.getResponses()) {
            sb.append("**`").append(tr.name()).append("`**\n\n");
            if (!tr.responseData().isBlank()) {
                sb.append("```\n").append(tr.responseData()).append("\n```\n\n");
            }
        }
    }

    private static void appendBlockquote(StringBuilder sb, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        for (var line : text.split("\n", -1)) {
            sb.append("> ").append(line).append("\n");
        }
        sb.append("\n");
    }

}
