package com.rorm.client.chat.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public sealed interface ChatNodeDTO permits
    ChatNodeDTO.Message,
    ChatNodeDTO.ToolCall,
    ChatNodeDTO.TrainingQueued,
    ChatNodeDTO.TrainingStarted,
    ChatNodeDTO.TrainingProgress,
    ChatNodeDTO.TrainingFinished,
    ChatNodeDTO.Failure {

    UUID id();

    Instant createdAt();

    record Message(
        UUID id,
        Instant createdAt,
        String text,
        Sender sender
    ) implements ChatNodeDTO {
        public enum Sender {USER, ASSISTANT, SYSTEM}
    }

    record ToolCall(
        UUID id,
        Instant createdAt,
        String description,
        Map<String, Object> response
    ) implements ChatNodeDTO {}

    record TrainingQueued(
        UUID id,
        Instant createdAt,
        UUID trainingId,
        String modelName
    ) implements ChatNodeDTO {}

    record TrainingStarted(
        UUID id,
        Instant createdAt,
        UUID trainingId
    ) implements ChatNodeDTO {}

    record TrainingProgress(
        UUID id,
        Instant createdAt,
        UUID trainingId,
        double progressPercentage
    ) implements ChatNodeDTO {}

    record TrainingFinished(
        UUID id,
        Instant createdAt,
        UUID trainingId,
        Map<String, Object> metrics
    ) implements ChatNodeDTO {}

    record Failure(
        UUID id,
        Instant createdAt,
        String reason,
        String errorMessage
    ) implements ChatNodeDTO {}
}
