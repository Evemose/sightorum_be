package com.rorm.ml.stream;

import com.rorm.ml.peristence.MLJobInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

@Slf4j
@org.springframework.stereotype.Component
@RequiredArgsConstructor
public class TrainingEventsSupport {

    private final TrainingFutureRegistry registry;

    public void resumeChat(MLJobInfo mlJobInfo, TrainingEvent event) {
        log.info("Completing future for training {}", mlJobInfo.jobId());
        registry.complete(UUID.fromString(mlJobInfo.jobId()), event);
    }

    public void handleTrainingFailure(MLJobInfo mlJobInfo, TrainingEvent event) {
        log.warn("Completing future exceptionally for training {}: {}", mlJobInfo.jobId(), event.error());
        registry.completeExceptionally(
            UUID.fromString(mlJobInfo.jobId()),
            new TrainingFailedException(event)
        );
    }

    public void handleTrainingProgress(MLJobInfo mlJobInfo, TrainingEvent event) {
        log.debug("Recording training progress for training {}: {}%",
            mlJobInfo.jobId(),
            event.progress() != null ? String.format("%.1f", event.progress() * 100) : "unknown");
    }
}
