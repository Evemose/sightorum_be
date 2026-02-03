package com.rorm.ai.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rorm.ai.RormAiService;
import com.rorm.ai.chat.node.FailureNode;
import com.rorm.ai.chat.node.TrainingFinishedNode;
import com.rorm.ai.chat.node.TrainingProgressNode;
import com.rorm.ml.stream.TrainingEvent;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@RequiredArgsConstructor
public class ChatResumeService {

    private final ChatProgressRepository repository;
    private final ObjectMapper objectMapper;
    private final RormAiService rormAiService;

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
        rormAiService.proceed(progress, "Resuming after training completion.");
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

        var failureNode = new FailureNode(
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

        var progressNode = new TrainingProgressNode(
            event.trainingId(),
            event.progress() != null ? event.progress() * 100 : 0.0
        );
        progress.addNode(progressNode);

        repository.save(progress);
    }
}
