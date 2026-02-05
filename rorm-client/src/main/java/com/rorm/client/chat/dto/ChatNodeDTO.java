package com.rorm.client.chat.dto;

import lombok.With;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public sealed interface ChatNodeDTO permits
    ChatNodeDTO.Message,
    ChatNodeDTO.ToolCall,
    ChatNodeDTO.TrainingQueued,
    ChatNodeDTO.TrainingStarted,
    ChatNodeDTO.TrainingProgress,
    ChatNodeDTO.TrainingFinished,
    ChatNodeDTO.Failure,
    ChatNodeDTO.AgentSubconclusion,
    ChatNodeDTO.Forked,
    ChatNodeDTO.Temporary {

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

    record AgentSubconclusion(
        UUID id,
        Instant createdAt,
        String summary,
        String keyInsight,
        String details,
        List<ResearchStepDTO> researchSteps,
        String conversationId
    ) implements ChatNodeDTO {
        public record ResearchStepDTO(
            String reasoning,
            String action,
            String observation
        ) {}
    }

    record Forked(
        UUID id,
        Instant createdAt,
        UUID forkPointId,
        String reason,
        String furtherInstructions
    ) implements ChatNodeDTO {}

    @With
    record Temporary(
        UUID id,
        Instant createdAt,
        String inProgressContent
    ) implements ChatNodeDTO {}
}
