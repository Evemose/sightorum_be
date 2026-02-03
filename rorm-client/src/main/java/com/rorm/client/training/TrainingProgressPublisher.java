package com.rorm.client.training;

import com.rorm.client.stream.SseEmitterRegistry;
import com.rorm.ml.stream.TrainingEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Publishes training progress events to SSE subscribers.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TrainingProgressPublisher {

    private final SseEmitterRegistry registry;
    private final AtomicLong eventCounter = new AtomicLong(0);

    /**
     * Publishes a training event to subscribers.
     */
    public void publish(TrainingEvent event) {
        var topic = buildTopic(event.trainingId());
        var eventId = String.valueOf(eventCounter.incrementAndGet());
        var dto = toDTO(event);
        var eventTypeName = getEventTypeName(dto);

        registry.publish(topic, eventTypeName, eventId, dto);
        log.debug("Published training event: trainingId={}, type={}", event.trainingId(), eventTypeName);

        // Complete the stream if training finished
        if (dto instanceof TrainingProgressDTO.Success || dto instanceof TrainingProgressDTO.Failed) {
            registry.complete(topic);
        }
    }

    private String buildTopic(UUID trainingId) {
        return "training:" + trainingId;
    }

    private TrainingProgressDTO toDTO(TrainingEvent event) {
        return switch (event.eventType()) {
            case TRAINING_STARTED -> new TrainingProgressDTO.Started(
                event.trainingId(),
                event.message(),
                event.timestamp()
            );
            case TRAINING_PROGRESS -> new TrainingProgressDTO.Progress(
                event.trainingId(),
                event.progress() != null ? event.progress() : 0.0,
                event.message(),
                event.timestamp()
            );
            case TRAINING_SUCCESS -> new TrainingProgressDTO.Success(
                event.trainingId(),
                extractModelPath(event),
                event.metrics(),
                event.timestamp()
            );
            case TRAINING_FAILED, VALIDATION_FAILED -> new TrainingProgressDTO.Failed(
                event.trainingId(),
                event.error(),
                event.errorCode(),
                event.timestamp()
            );
        };
    }

    private String getEventTypeName(TrainingProgressDTO dto) {
        return switch (dto) {
            case TrainingProgressDTO.Started s -> "training_started";
            case TrainingProgressDTO.Progress p -> "training_progress";
            case TrainingProgressDTO.Success s -> "training_success";
            case TrainingProgressDTO.Failed f -> "training_failed";
        };
    }

    private String extractModelPath(TrainingEvent event) {
        if (event.metadata() != null && event.metadata().containsKey("modelPath")) {
            return String.valueOf(event.metadata().get("modelPath"));
        }
        if (event.metrics() != null && event.metrics().containsKey("modelPath")) {
            return String.valueOf(event.metrics().get("modelPath"));
        }
        return null;
    }

    /**
     * Checks if there are any active subscribers for a training.
     */
    public boolean hasSubscribers(UUID trainingId) {
        return registry.hasSubscribers(buildTopic(trainingId));
    }

    public sealed interface TrainingProgressDTO permits
        TrainingProgressDTO.Started,
        TrainingProgressDTO.Progress,
        TrainingProgressDTO.Success,
        TrainingProgressDTO.Failed {

        UUID trainingId();

        Instant timestamp();

        record Started(
            UUID trainingId,
            String message,
            Instant timestamp
        ) implements TrainingProgressDTO {}

        record Progress(
            UUID trainingId,
            double progress,
            String message,
            Instant timestamp
        ) implements TrainingProgressDTO {}

        record Success(
            UUID trainingId,
            String modelPath,
            Map<String, Object> metrics,
            Instant timestamp
        ) implements TrainingProgressDTO {}

        record Failed(
            UUID trainingId,
            String error,
            String errorCode,
            Instant timestamp
        ) implements TrainingProgressDTO {}
    }
}
