package com.rorm.client.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rorm.ai.chat.node.*;
import com.rorm.client.chat.dto.ChatNodeDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class ChatNodeMapper {

    private final ObjectMapper objectMapper;

    @SuppressWarnings("unchecked")
    public ChatNodeDTO toDTO(ChatNode node) {
        return switch (node) {
            case MessageNode msg -> new ChatNodeDTO.Message(
                msg.getId(),
                msg.getCreatedAt(),
                msg.getContent(),
                mapSender(msg.getSender())
            );
            case ToolCallNode tool -> new ChatNodeDTO.ToolCall(
                tool.getId(),
                tool.getCreatedAt(),
                tool.getDescription(),
                objectMapper.convertValue(tool.getResponse(), Map.class)
            );
            case TrainingQueuedNode training -> new ChatNodeDTO.TrainingQueued(
                training.getId(),
                training.getCreatedAt(),
                training.getTrainingId(),
                extractModelName(training)
            );
            case TrainingStartedNode training -> new ChatNodeDTO.TrainingStarted(
                training.getId(),
                training.getCreatedAt(),
                training.getTrainingId()
            );
            case TrainingProgressNode training -> new ChatNodeDTO.TrainingProgress(
                training.getId(),
                training.getCreatedAt(),
                training.getTrainingId(),
                training.getProgressPercentage()
            );
            case TrainingFinishedNode training -> new ChatNodeDTO.TrainingFinished(
                training.getId(),
                training.getCreatedAt(),
                training.getTrainingId(),
                objectMapper.convertValue(training.getMetrics(), Map.class)
            );
            case FailureNode failure -> new ChatNodeDTO.Failure(
                failure.getId(),
                failure.getCreatedAt(),
                failure.getMessage(),
                failure.getDetail() != null ? failure.getDetail().toString() : null
            );
            default -> throw new IllegalArgumentException("Unknown node type: " + node.getClass().getSimpleName());
        };
    }

    private ChatNodeDTO.Message.Sender mapSender(Sender sender) {
        return switch (sender) {
            case USER -> ChatNodeDTO.Message.Sender.USER;
            case ASSISTANT -> ChatNodeDTO.Message.Sender.ASSISTANT;
            case SYSTEM, TOOL_CALL -> ChatNodeDTO.Message.Sender.SYSTEM;
        };
    }

    private String extractModelName(TrainingQueuedNode node) {
        var payload = node.getRequestPayload();
        if (payload != null && payload.has("modelName")) {
            return payload.get("modelName").asText();
        }
        return "unknown";
    }
}
