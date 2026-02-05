package com.rorm.ai.tools.mapper;

import com.rorm.ai.chat.node.*;
import com.rorm.ai.tools.dto.ChatNodeDetailDTO;
import com.rorm.ai.tools.dto.ChatNodeSummaryDTO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * MapStruct mapper for converting ChatNode entities to DTOs.
 */
@Mapper(componentModel = "spring")
public interface ChatNodeMapper {

    default ChatNodeSummaryDTO toSummaryDTO(ChatNode node) {
        return switch (node) {
            case MessageNode msg -> toMessageSummaryDTO(msg);
            case ChatForkedNode forked -> toForkedSummaryDTO(forked);
            case TrainingQueuedNode queued -> toQueuedSummaryDTO(queued);
            case TrainingStartedNode started -> toStartedSummaryDTO(started);
            case TrainingProgressNode progress -> toProgressSummaryDTO(progress);
            case TrainingFinishedNode finished -> toFinishedSummaryDTO(finished);
            case TrainingFailedNode failed -> toFailedSummaryDTO(failed);
            case AgentSubconclusionNode subconclusion -> toSubconclusionSummaryDTO(subconclusion);
            default -> new ChatNodeSummaryDTO(
                node.getId(),
                node.getCreatedAt(),
                node.getClass().getSimpleName(),
                node.toString()
            );
        };
    }

    @Mapping(target = "type", constant = "MessageNode")
    @Mapping(target = "summary", expression = "java(truncate(node.getContent(), 100))")
    ChatNodeSummaryDTO toMessageSummaryDTO(MessageNode node);

    @Mapping(target = "type", constant = "ChatForkedNode")
    @Mapping(target = "summary", source = "reason")
    ChatNodeSummaryDTO toForkedSummaryDTO(ChatForkedNode node);

    @Mapping(target = "type", constant = "TrainingQueuedNode")
    @Mapping(target = "summary", expression = "java(\"Training queued: \" + node.getTrainingId())")
    ChatNodeSummaryDTO toQueuedSummaryDTO(TrainingQueuedNode node);

    @Mapping(target = "type", constant = "TrainingStartedNode")
    @Mapping(target = "summary", expression = "java(\"Training started: \" + node.getTrainingId())")
    ChatNodeSummaryDTO toStartedSummaryDTO(TrainingStartedNode node);

    @Mapping(target = "type", constant = "TrainingProgressNode")
    @Mapping(target = "summary", expression = "java(\"Training progress: \" + node.getProgressPercentage() + \"%\")")
    ChatNodeSummaryDTO toProgressSummaryDTO(TrainingProgressNode node);

    @Mapping(target = "type", constant = "TrainingFinishedNode")
    @Mapping(target = "summary", expression = "java(\"Training finished: \" + node.getTrainingId())")
    ChatNodeSummaryDTO toFinishedSummaryDTO(TrainingFinishedNode node);

    @Mapping(target = "type", constant = "TrainingFailedNode")
    @Mapping(target = "summary", expression = "java(\"Training failed: \" + node.getMessage())")
    ChatNodeSummaryDTO toFailedSummaryDTO(TrainingFailedNode node);

    @Mapping(target = "type", constant = "AgentSubconclusionNode")
    ChatNodeSummaryDTO toSubconclusionSummaryDTO(AgentSubconclusionNode node);

    // Detail mappings - using default methods with switch expression
    // because MapStruct cannot generate implementations for sealed interface targets

    default ChatNodeDetailDTO toDetailDTO(ChatNode node) {
        return toDetailDTO(node, null);
    }

    default ChatNodeDetailDTO toDetailDTO(ChatNode node, List<Message> conversationMemory) {
        return switch (node) {
            case MessageNode msg -> toMessageDetailDTO(msg);
            case ChatForkedNode forked -> toForkedDetailDTO(forked);
            case TrainingQueuedNode queued -> toQueuedDetailDTO(queued);
            case TrainingStartedNode started -> toStartedDetailDTO(started);
            case TrainingProgressNode progress -> toProgressDetailDTO(progress);
            case TrainingFinishedNode finished -> toFinishedDetailDTO(finished);
            case TrainingFailedNode failed -> toFailedDetailDTO(failed);
            case AgentSubconclusionNode subconclusion -> toSubconclusionDetailDTO(subconclusion, conversationMemory);
            default -> throw new IllegalArgumentException("Unknown node type: " + node.getClass().getSimpleName());
        };
    }

    @Mapping(target = "type", constant = "MessageNode")
    @Mapping(target = "sender", expression = "java(node.getSender().name())")
    ChatNodeDetailDTO.MessageDTO toMessageDetailDTO(MessageNode node);

    @Mapping(target = "type", constant = "ChatForkedNode")
    @Mapping(target = "forkPointId", source = "forkPoint.id")
    ChatNodeDetailDTO.ChatForkedDTO toForkedDetailDTO(ChatForkedNode node);

    @Mapping(target = "type", constant = "TrainingQueuedNode")
    ChatNodeDetailDTO.TrainingQueuedDTO toQueuedDetailDTO(TrainingQueuedNode node);

    @Mapping(target = "type", constant = "TrainingStartedNode")
    ChatNodeDetailDTO.TrainingStartedDTO toStartedDetailDTO(TrainingStartedNode node);

    @Mapping(target = "type", constant = "TrainingProgressNode")
    ChatNodeDetailDTO.TrainingProgressDTO toProgressDetailDTO(TrainingProgressNode node);

    @Mapping(target = "type", constant = "TrainingFinishedNode")
    ChatNodeDetailDTO.TrainingFinishedDTO toFinishedDetailDTO(TrainingFinishedNode node);

    @Mapping(target = "type", constant = "TrainingFailedNode")
    ChatNodeDetailDTO.TrainingFailedDTO toFailedDetailDTO(TrainingFailedNode node);

    @Mapping(target = "type", constant = "AgentSubconclusionNode")
    @Mapping(target = "conversationMemory", source = "conversationMemory")
    ChatNodeDetailDTO.AgentSubconclusionDTO toSubconclusionDetailDTO(AgentSubconclusionNode node, List<Message> conversationMemory);

    ChatNodeDetailDTO.ResearchStepDTO toResearchStepDTO(ResearchStep step);

    default String truncate(String text, int maxLength) {
        if (text == null) {
            return null;
        }
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength - 3) + "...";
    }
}
