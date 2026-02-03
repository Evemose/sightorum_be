package com.rorm.ml.stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rorm.ai.chat.ChatProgress;
import com.rorm.ai.chat.ChatProgressRepository;
import com.rorm.ai.chat.ChatResumeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.stream.StreamListener;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

@Slf4j
@RequiredArgsConstructor
public class TrainingStreamListener implements StreamListener<String, MapRecord<String, String, String>> {

    private final ChatProgressRepository chatProgressRepository;
    private final ChatResumeService chatResumeService;
    private final ObjectMapper objectMapper;

    @Override
    public void onMessage(MapRecord<String, String, String> message) {
        log.debug("Received stream message: {}", message.getId());

        try {
            var event = parseEvent(message.getValue());
            handleEvent(event);
        } catch (Exception e) {
            log.error("Failed to process stream message: {}", e.getMessage(), e);
        }
    }

    private TrainingEvent parseEvent(Map<String, String> data) throws JsonProcessingException {
        var payload = data.get("payload");
        if (payload != null) {
            return objectMapper.readValue(payload, TrainingEvent.class);
        }

        var trainingIdStr = data.get("training_id");
        if (trainingIdStr == null) {
            throw new IllegalArgumentException("Missing training_id in message");
        }

        data = fillDefaults(data);

        return objectMapper.convertValue(data, TrainingEvent.class);
    }

    private void handleEvent(TrainingEvent event) {
        log.info("Processing training event: {} for training {}",
            event.eventType(), event.trainingId());

        switch (event.eventType()) {
            case TRAINING_STARTED -> handleStarted(event);
            case TRAINING_PROGRESS -> handleProgress(event);
            case TRAINING_SUCCESS -> handleSuccess(event);
            case TRAINING_FAILED, VALIDATION_FAILED -> handleFailed(event);
        }
    }

    private Map<String, String> fillDefaults(Map<String, String> data) {
        var newData = new HashMap<>(data);
        newData.putIfAbsent("timestamp", Instant.now().toString());
        return newData;
    }

    private void handleStarted(TrainingEvent event) {
        log.info("Training started: {} - {}", event.trainingId(), event.message());
    }

    private void handleProgress(TrainingEvent event) {
        log.debug("Training progress: {} - {}%",
            event.trainingId(),
            event.progress() != null ? String.format("%.1f", event.progress() * 100) : "unknown");

        doWithChatProgress(event, progress -> chatResumeService.handleTrainingProgress(progress, event));
    }

    private void handleSuccess(TrainingEvent event) {
        log.info("Training succeeded: {} - {}", event.trainingId(), event.message());

        doWithChatProgress(event, progress -> chatResumeService.resumeChat(progress, event));
    }

    private void handleFailed(TrainingEvent event) {
        log.warn("Training failed: {} - {} ({})",
            event.trainingId(), event.error(), event.errorCode());

        doWithChatProgress(event, progress -> chatResumeService.handleTrainingFailure(progress, event));
    }

    private void doWithChatProgress(TrainingEvent event, Consumer<ChatProgress> consumer) {
        chatProgressRepository.findByTrainingId(event.trainingId())
            .ifPresentOrElse(
                consumer,
                () -> log.debug("No chat progress found for training {}", event.trainingId())
            );
    }
}
