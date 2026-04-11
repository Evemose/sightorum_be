package com.rorm.ai.chat;

import com.rorm.StepJournal;
import com.rorm.ai.anthropic.ServerToolGeneration;
import com.rorm.ai.anthropic.ThinkingGeneration;
import com.rorm.ai.anthropic.ToolRoundGeneration;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.Generation;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class ChatMemoryManager {

    private final ChatMemoryRepository repository;

    public List<Message> loadHistory(String conversationId, Set<MemoryInclude> includes) {
        var messages = repository.findByConversationId(conversationId);
        var filtered = new ArrayList<Message>(messages.size());
        for (var message : messages) {
            switch (message) {
                case ThinkingMessage _ when !includes.contains(MemoryInclude.THINKING) -> {
                }
                case ServerToolMessage _ when !includes.contains(MemoryInclude.SERVER_TOOLS) -> {
                }
                case ToolResponseMessage _ when !includes.contains(MemoryInclude.TOOL_RESPONSES) -> {
                }
                case AssistantMessage am when !am.getToolCalls().isEmpty()
                                              && !includes.contains(MemoryInclude.TOOL_CALLS) ->
                    filtered.add(AssistantMessage.builder()
                        .content(Objects.requireNonNullElse(am.getText(), ""))
                        .properties(am.getMetadata())
                        .build());
                default -> filtered.add(message);
            }
        }
        return filtered;
    }

    public void saveMessages(
        String conversationId,
        Message userMessage,
        List<Generation> generations,
        StepJournal journal
    ) {
        journal.run("save-memory", () -> {
            var existing = repository.findByConversationId(conversationId);
            var all = new ArrayList<>(existing);

            var systemMsg = existing.stream().filter(SystemMessage.class::isInstance).findFirst();

            if (userMessage != null) {
                all.add(userMessage);
            }
            all.addAll(generationsToMessages(generations));

            if (systemMsg.isPresent()) {
                all.removeIf(SystemMessage.class::isInstance);
                all.addFirst(systemMsg.get());
            }

            repository.saveAll(conversationId, all);
            return true;
        });
    }

    static List<Message> generationsToMessages(List<Generation> generations) {
        var messages = new ArrayList<Message>();
        for (var gen : generations) {
            switch (gen) {
                case ThinkingGeneration tg -> messages.add(new ThinkingMessage(tg.getThinkingText()));
                case ToolRoundGeneration trg -> {
                    messages.add(trg.getOutput());
                    messages.add(trg.getToolResponse());
                }
                case ServerToolGeneration stg -> messages.add(stg.getServerToolMessage());
                default -> {
                    var output = gen.getOutput();
                    if (output.getText() != null && !output.getText().isEmpty()) {
                        messages.add(output);
                    }
                }
            }
        }
        return messages;
    }
}
