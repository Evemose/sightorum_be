package com.rorm.ai.chat;

import lombok.Getter;
import org.springframework.ai.chat.messages.Message;
import org.springframework.context.ApplicationEvent;

@Getter
public class MessageAddedEvent extends ApplicationEvent {
    private final String conversationId;
    private final Message message;

    public MessageAddedEvent(Object source, String conversationId, Message message) {
        super(source);
        this.conversationId = conversationId;
        this.message = message;
    }
}