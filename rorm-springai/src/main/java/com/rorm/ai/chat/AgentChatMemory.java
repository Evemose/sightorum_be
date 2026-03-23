package com.rorm.ai.chat;

import com.rorm.StepJournal;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@RequiredArgsConstructor
public class AgentChatMemory implements ChatMemory {

    private final AtomicInteger addCounter = new AtomicInteger(0);
    private final ChatMemoryRepository repository;
    private final StepJournal journal;

    @Override
    public void add(String conversationId, List<Message> messages) {
        journal.run("add-msgs-" + addCounter.getAndIncrement(), () -> {
            var current = repository.findByConversationId(conversationId);
            var systemMsg = messages.stream().filter(SystemMessage.class::isInstance).findFirst();
            if (systemMsg.isPresent()) {
                current = new ArrayList<>(current);
                current.addAll(messages);
                current.removeIf(SystemMessage.class::isInstance);
                current.addFirst(systemMsg.get());
            }
            repository.saveAll(conversationId, current);
            return true;
        });
    }

    @Override
    public List<Message> get(String conversationId) {
        // mutations are journaled, so get is deterministic assuming the agent owns its own conversation exclusively
        // but TODO: add proper serialization for messages. Currently should not affect but shaky
        return repository.findByConversationId(conversationId);
    }

    @Override
    public void clear(String conversationId) {
        throw new UnsupportedOperationException("Agent chat memory is append-only");
    }
}
