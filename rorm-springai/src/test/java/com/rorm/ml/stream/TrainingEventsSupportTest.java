package com.rorm.ml.stream;

import com.rorm.ml.peristence.MLJobInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("TrainingEventsSupport")
class TrainingEventsSupportTest {

    private TrainingFutureRegistry registry;
    private TrainingEventsSupport support;

    private static MLJobInfo trainingInfo(UUID trainingId) {
        return new MLJobInfo(
            trainingId.toString(), "Test reason", "Check metrics",
            "test_schema", "SELECT 1", "target", List.of("f1", "f2"), null
        );
    }

    private static TrainingEvent successEvent(UUID trainingId) {
        return new TrainingEvent(
            trainingId, TrainingEventType.TRAINING_SUCCESS, Instant.now(),
            null, "Done", Map.of("accuracy", 0.92), null, null, Map.of()
        );
    }

    private static TrainingEvent failedEvent(UUID trainingId, String error) {
        return new TrainingEvent(
            trainingId, TrainingEventType.TRAINING_FAILED, Instant.now(),
            null, null, null, error, "ERR_CONVERGENCE", Map.of()
        );
    }

    private static TrainingEvent progressEvent(UUID trainingId, double progress) {
        return new TrainingEvent(
            trainingId, TrainingEventType.TRAINING_PROGRESS, Instant.now(),
            progress, "Training in progress", null, null, null, Map.of()
        );
    }

    @BeforeEach
    void setUp() {
        registry = new TrainingFutureRegistry();
        support = new TrainingEventsSupport(registry);
    }

    @Nested
    @DisplayName("resumeChat")
    class ResumeChat {

        @Test
        @DisplayName("completes the registered future with the training event")
        void completesRegisteredFuture() throws Exception {
            var trainingId = UUID.randomUUID();
            var future = registry.register(trainingId);
            var event = successEvent(trainingId);
            var info = trainingInfo(trainingId);

            support.resumeChat(info, event);

            assertThat(future.get(1, TimeUnit.SECONDS))
                .satisfies(result -> {
                    assertThat(result.trainingId()).isEqualTo(trainingId);
                    assertThat(result.isSuccess()).isTrue();
                    assertThat(result.metrics()).containsEntry("accuracy", 0.92);
                });
        }

        @Test
        @DisplayName("is a no-op when no future was registered for the trainingId")
        void noopWhenNoFutureRegistered() {
            var trainingId = UUID.randomUUID();
            var event = successEvent(trainingId);

            // should not throw
            support.resumeChat(trainingInfo(trainingId), event);
        }
    }

    @Nested
    @DisplayName("handleTrainingFailure")
    class HandleTrainingFailure {

        @Test
        @DisplayName("completes the future exceptionally with TrainingFailedException")
        void completesExceptionally() {
            var trainingId = UUID.randomUUID();
            var future = registry.register(trainingId);
            var event = failedEvent(trainingId, "Convergence error");
            var info = trainingInfo(trainingId);

            support.handleTrainingFailure(info, event);

            assertThat(future).isCompletedExceptionally();
            assertThatThrownBy(() -> future.get(1, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(TrainingFailedException.class)
                .satisfies(ex -> {
                    var tfe = (TrainingFailedException) ex.getCause();
                    assertThat(tfe.event().trainingId()).isEqualTo(trainingId);
                    assertThat(tfe.event().error()).isEqualTo("Convergence error");
                });
        }
    }

    @Nested
    @DisplayName("handleTrainingProgress")
    class HandleTrainingProgress {

        @Test
        @DisplayName("does not complete or fail the registered future")
        void doesNotAffectFuture() {
            var trainingId = UUID.randomUUID();
            var future = registry.register(trainingId);
            var event = progressEvent(trainingId, 0.5);

            support.handleTrainingProgress(trainingInfo(trainingId), event);

            assertThat(future).isNotDone();
        }
    }
}
