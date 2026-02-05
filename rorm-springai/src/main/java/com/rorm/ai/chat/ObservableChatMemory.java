package com.rorm.ai.chat;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

@RequiredArgsConstructor
public class ObservableChatMemory implements ChatMemory {

    private final ChatMemory delegate;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    public void add(String conversationId, List<Message> messages) {
        delegate.add(conversationId, messages);
        for (var message : messages) {
            eventPublisher.publishEvent(
                new MessageAddedEvent(this, conversationId, message)
            );
        }
    }

    @Override
    public void add(String conversationId, Message message) {
        delegate.add(conversationId, message);
        eventPublisher.publishEvent(
            new MessageAddedEvent(this, conversationId, message)
        );
    }

    @Override
    public List<Message> get(String conversationId) {
        return delegate.get(conversationId);
    }

    @Override
    public void clear(String conversationId) {
        delegate.clear(conversationId);
    }
}
