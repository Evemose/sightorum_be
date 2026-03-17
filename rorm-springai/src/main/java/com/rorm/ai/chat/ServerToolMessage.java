package com.rorm.ai.chat;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;

import java.util.Map;

public record ServerToolMessage(
    String toolName,
    String inputJson,
    String outputJson
) implements Message {

    @Override
    public MessageType getMessageType() {
        return MessageType.ASSISTANT;
    }

    @Override
    public String getText() {
        return "[" + toolName + "] " + (inputJson != null ? inputJson : "");
    }

    @Override
    public Map<String, Object> getMetadata() {
        return Map.of();
    }
}
