package com.rorm.client.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rorm.ai.chat.node.*;
import com.rorm.client.chat.dto.ChatNodeDTO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

/**
 * Maps ChatNode entities to DTOs for client consumption.
 * Uses MapStruct for subtype mappings with a manual switch expression
 * for the sealed interface dispatch.
 */
@Mapper(componentModel = "spring")
@SuppressWarnings("SpringJavaAutowiredFieldsWarningInspection")
public abstract class ClientChatNodeMapper {

    @Autowired
    protected ObjectMapper objectMapper;

    /**
     * Converts a ChatNode entity to its corresponding DTO.
     * This is a non-abstract method that MapStruct won't try to generate.
     */
    public ChatNodeDTO toDTO(ChatNode node) {
        return switch (node) {
            case MessageNode msg -> toMessageDTO(msg);
            case TrainingQueuedNode training -> toTrainingQueuedDTO(training);
            case TrainingStartedNode training -> toTrainingStartedDTO(training);
            case TrainingProgressNode training -> toTrainingProgressDTO(training);
            case TrainingFinishedNode training -> toTrainingFinishedDTO(training);
            case TrainingFailedNode failure -> toFailureDTO(failure);
            case ChatForkedNode forked -> toForkedDTO(forked);
            case AgentSubconclusionNode subconclusion -> toSubconclusionDTO(subconclusion);
            default -> throw new IllegalArgumentException("Unknown node type: " + node.getClass().getSimpleName());
        };
    }

    @Mapping(target = "text", source = "content")
    @Mapping(target = "sender", expression = "java(mapSender(msg.getSender()))")
    protected abstract ChatNodeDTO.Message toMessageDTO(MessageNode msg);

    @Mapping(target = "modelName", expression = "java(extractModelName(training))")
    protected abstract ChatNodeDTO.TrainingQueued toTrainingQueuedDTO(TrainingQueuedNode training);

    protected abstract ChatNodeDTO.TrainingStarted toTrainingStartedDTO(TrainingStartedNode training);

    protected abstract ChatNodeDTO.TrainingProgress toTrainingProgressDTO(TrainingProgressNode training);

    @Mapping(target = "metrics", expression = "java(toMap(training.getMetrics()))")
    protected abstract ChatNodeDTO.TrainingFinished toTrainingFinishedDTO(TrainingFinishedNode training);

    @Mapping(target = "reason", source = "message")
    @Mapping(target = "errorMessage", expression = "java(failure.getPayload() != null ? failure.getPayload().toString() : null)")
    protected abstract ChatNodeDTO.Failure toFailureDTO(TrainingFailedNode failure);

    @Mapping(target = "forkPointId", source = "forkPoint.id")
    protected abstract ChatNodeDTO.Forked toForkedDTO(ChatForkedNode forked);

    @Mapping(target = "researchSteps", expression = "java(mapResearchSteps(subconclusion.getResearchSteps()))")
    protected abstract ChatNodeDTO.AgentSubconclusion toSubconclusionDTO(AgentSubconclusionNode subconclusion);

    protected ChatNodeDTO.Message.Sender mapSender(Sender sender) {
        return switch (sender) {
            case USER -> ChatNodeDTO.Message.Sender.USER;
            case ASSISTANT -> ChatNodeDTO.Message.Sender.ASSISTANT;
            case SYSTEM, TOOL_CALL -> ChatNodeDTO.Message.Sender.SYSTEM;
        };
    }

    protected List<ChatNodeDTO.AgentSubconclusion.ResearchStepDTO> mapResearchSteps(List<ResearchStep> steps) {
        if (steps == null) {
            return List.of();
        }
        return steps.stream()
            .map(step -> new ChatNodeDTO.AgentSubconclusion.ResearchStepDTO(
                step.reasoning(),
                step.action(),
                step.observation()
            ))
            .toList();
    }

    protected String extractModelName(TrainingQueuedNode node) {
        var payload = node.getRequestPayload();
        if (payload.has("modelName")) {
            return payload.get("modelName").asText();
        }
        return "unknown";
    }

    @SuppressWarnings("unchecked")
    protected Map<String, Object> toMap(ObjectNode node) {
        return objectMapper.convertValue(node, Map.class);
    }
}
