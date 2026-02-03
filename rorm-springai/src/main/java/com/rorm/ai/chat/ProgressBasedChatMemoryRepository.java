package com.rorm.ai.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rorm.ai.chat.node.*;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Stream;

@RequiredArgsConstructor
public class ProgressBasedChatMemoryRepository implements ChatMemoryRepository {

    private final ObjectMapper objectMapper;
    private final ChatProgressRepository progressRepository;

    @Override
    public List<String> findConversationIds() {
        return progressRepository.findAllIds();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Message> findByConversationId(String conversationId) {
        return parseUuid(conversationId)
            .flatMap(progressRepository::findById)
            .map(cp -> convertToAiMessages(cp, cp.getMemoryMessages()))
            .orElseGet(List::of);
    }

    private Optional<UUID> parseUuid(String s) {
        try {
            return Optional.of(UUID.fromString(s));
        } catch (IllegalArgumentException _) {
            return Optional.empty();
        }
    }

    private List<Message> convertToAiMessages(ChatProgress cp, List<MessageLike> chatMessages) {
        return chatMessages.stream()
            .<Message>map(msg -> switch (msg.getSender()) {
                case Sender.USER -> new UserMessage(msg.getContent());
                case Sender.ASSISTANT -> new AssistantMessage(msg.getContent());
                case Sender.SYSTEM -> new SystemMessage(msg.getContent());
                case Sender.TOOL_CALL -> new ToolResponseMessage(Collections.singletonList(new ToolResponse(
                    cp.getId().toString(), msg.getTitle(), msg.getContent()
                )));
            })
            .toList();
    }

    @Override
    @Transactional
    public void saveAll(String conversationId, List<Message> messages) {
        var progress = parseUuid(conversationId)
            .flatMap(progressRepository::findById)
            .orElseGet(() -> new ChatProgress());
        progress.setMemoryNodes(convertToChatMessages(messages));
        progressRepository.save(progress);
    }

    private List<ChatNode> convertToChatMessages(List<Message> messages) {
        return messages.stream()
            .<ChatNode>flatMap(msg -> switch (msg) {
                case UserMessage userMsg -> Stream.of(MessageNode.user(userMsg.getText()));
                case AssistantMessage assistantMsg -> Stream.of(MessageNode.assistant(
                    Objects.requireNonNullElse(assistantMsg.getText(), "")
                ));
                case SystemMessage systemMsg -> Stream.of(MessageNode.system(systemMsg.getText()));
                case ToolResponseMessage toolResponseMsg -> toolResponseMsg.getResponses().stream()
                    .map(resp -> new ToolCallNode(
                        UUID.fromString(resp.id()),
                        resp.name(),
                        readTreeUnchecked(resp.responseData())
                    ));
                default -> throw new IllegalArgumentException("Unknown message type: " + msg.getClass());
            })
            .toList();
    }

    @SneakyThrows
    private ObjectNode readTreeUnchecked(String s) {
        return (ObjectNode) objectMapper.readTree(s);
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        progressRepository.deleteById(UUID.fromString(conversationId));
    }
}
