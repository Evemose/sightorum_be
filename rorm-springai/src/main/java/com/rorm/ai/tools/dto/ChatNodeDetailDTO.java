package com.rorm.ai.tools.dto;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.ai.chat.messages.Message;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Detailed DTOs for chat nodes, containing full entity data.
 * Used for retrieving complete node information.
 */
public sealed interface ChatNodeDetailDTO permits
    ChatNodeDetailDTO.MessageDTO,
    ChatNodeDetailDTO.ChatForkedDTO,
    ChatNodeDetailDTO.TrainingQueuedDTO,
    ChatNodeDetailDTO.TrainingStartedDTO,
    ChatNodeDetailDTO.TrainingProgressDTO,
    ChatNodeDetailDTO.TrainingFinishedDTO,
    ChatNodeDetailDTO.TrainingFailedDTO,
    ChatNodeDetailDTO.AgentSubconclusionDTO {

    UUID id();

    Instant createdAt();

    String type();

    record MessageDTO(
        UUID id,
        Instant createdAt,
        String type,
        String content,
        String sender
    ) implements ChatNodeDetailDTO {}

    record ChatForkedDTO(
        UUID id,
        Instant createdAt,
        String type,
        UUID forkPointId,
        String reason,
        String furtherInstructions
    ) implements ChatNodeDetailDTO {}

    record TrainingQueuedDTO(
        UUID id,
        Instant createdAt,
        String type,
        UUID trainingId,
        ObjectNode requestPayload
    ) implements ChatNodeDetailDTO {}

    record TrainingStartedDTO(
        UUID id,
        Instant createdAt,
        String type,
        UUID trainingId,
        ObjectNode payload
    ) implements ChatNodeDetailDTO {}

    record TrainingProgressDTO(
        UUID id,
        Instant createdAt,
        String type,
        UUID trainingId,
        Double progressPercentage
    ) implements ChatNodeDetailDTO {}

    record TrainingFinishedDTO(
        UUID id,
        Instant createdAt,
        String type,
        UUID trainingId,
        ObjectNode metrics
    ) implements ChatNodeDetailDTO {}

    record TrainingFailedDTO(
        UUID id,
        Instant createdAt,
        String type,
        UUID trainingId,
        String message,
        ObjectNode payload
    ) implements ChatNodeDetailDTO {}

    record ResearchStepDTO(
        String reasoning,
        String action,
        String observation
    ) {}

    record AgentSubconclusionDTO(
        UUID id,
        Instant createdAt,
        String type,
        String summary,
        String details,
        List<ResearchStepDTO> researchSteps,
        String conversationId,
        List<Message> conversationMemory
    ) implements ChatNodeDetailDTO {}
}
