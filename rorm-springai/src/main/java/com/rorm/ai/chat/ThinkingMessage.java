package com.rorm.ai.chat;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;

import java.util.Map;

public record ThinkingMessage(String text) implements Message {

    @Override
    public MessageType getMessageType() {
        return MessageType.ASSISTANT;
    }

    @Override
    public String getText() {
        return text;
    }

    @Override
    public Map<String, Object> getMetadata() {
        return Map.of();
    }
}
