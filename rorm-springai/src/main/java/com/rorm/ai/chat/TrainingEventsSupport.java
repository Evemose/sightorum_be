package com.rorm.ai.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rorm.ai.chat.node.ChatNodeRepository;
import com.rorm.ai.chat.node.TrainingFailedNode;
import com.rorm.ai.chat.node.TrainingFinishedNode;
import com.rorm.ai.chat.node.TrainingProgressNode;
import com.rorm.ml.stream.TrainingEvent;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@Slf4j
@RequiredArgsConstructor
public class TrainingEventsSupport {

    private final ChatProgressRepository repository;
    private final ChatNodeRepository chatNodeRepository;
    private final ObjectMapper objectMapper;
    private final AiChatService aiChatService;

    @Transactional
    public void resumeChat(ChatProgress progress, TrainingEvent event) {
        log.info("Resuming chat for conversation {} after training {}",
            progress.getConversationId(), event.trainingId());
        var finishedNode = new TrainingFinishedNode(
            event.trainingId(),
            toObjectNode(event.metrics())
        );
        progress.addNode(finishedNode);
        repository.save(progress);

        var request = ChatRequest.proceedingOnSchema(extractSchemaName(progress), progress)
            .ask("Resuming after training completion.");
        aiChatService.call(request);
    }

    private String extractSchemaName(ChatProgress progress) {
        var modelSpace = progress.getModelSpace();
        if (modelSpace != null && !modelSpace.roots().isEmpty()) {
            return modelSpace.roots().iterator().next().primaryTableName();
        }
        return "unknown";
    }

    @SneakyThrows
    private ObjectNode toObjectNode(Object value) {
        if (value == null) {
            return objectMapper.createObjectNode();
        }
        return objectMapper.valueToTree(value);
    }

    @Transactional
    public void handleTrainingFailure(ChatProgress progress, TrainingEvent event) {
        log.warn("Handling training failure for conversation {}: {}",
            progress.getConversationId(), event.error());

        var failureNode = new TrainingFailedNode(
            event.trainingId(),
            String.format("Training failed: %s (Error code: %s)", event.error(), event.errorCode()),
            toObjectNode(event.metadata())
        );
        progress.addNode(failureNode);

        progress.fail();
        repository.save(progress);
    }

    @Transactional
    public void handleTrainingProgress(ChatProgress progress, TrainingEvent event) {
        log.debug("Recording training progress for conversation {}: {}%",
            progress.getConversationId(),
            event.progress() != null ? String.format("%.1f", event.progress() * 100) : "unknown");

        var progressNode = chatNodeRepository.findTrainingProgressByTrainingId((event.trainingId()))
            .orElseGet(() -> new TrainingProgressNode(
                event.trainingId(),
                Objects.requireNonNullElse(event.progress(), 0d)
            ));

        progressNode.setProgressPercentage(Objects.requireNonNullElse(event.progress(), 0d));
        repository.save(progress);
    }
}
