package com.rorm.ml.stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("TrainingFutureRegistry")
class TrainingFutureRegistryTest {

    private final TrainingFutureRegistry registry = new TrainingFutureRegistry();

    private static TrainingEvent successEvent(UUID trainingId) {
        return new TrainingEvent(
            trainingId, TrainingEventType.TRAINING_SUCCESS, Instant.now(),
            null, "Model trained", Map.of("accuracy", 0.95), null, null, Map.of()
        );
    }

    private static TrainingEvent failedEvent(UUID trainingId, String error) {
        return new TrainingEvent(
            trainingId, TrainingEventType.TRAINING_FAILED, Instant.now(),
            null, null, null, error, "ERR_OOM", Map.of()
        );
    }

    @Nested
    @DisplayName("register and complete")
    class RegisterAndComplete {

        @Test
        @DisplayName("registered future completes with the provided event")
        void registeredFutureCompletesWithEvent() throws Exception {
            var trainingId = UUID.randomUUID();
            var event = successEvent(trainingId);

            var future = registry.register(trainingId);
            registry.complete(trainingId, event);

            assertThat(future.get(1, TimeUnit.SECONDS)).isSameAs(event);
        }

        @Test
        @DisplayName("complete keeps the entry in the registry (explicit remove needed)")
        void completeKeepsEntry() {
            var trainingId = UUID.randomUUID();
            var event = successEvent(trainingId);
            registry.register(trainingId);
            registry.complete(trainingId, event);

            assertThat(registry.get(trainingId)).isNotNull().isDone();
            // Explicit remove cleans up
            registry.remove(trainingId);
            assertThat(registry.get(trainingId)).isNull();
        }

        @Test
        @DisplayName("complete on unknown trainingId is a no-op")
        void completeUnknownIdIsNoop() {
            registry.complete(UUID.randomUUID(), successEvent(UUID.randomUUID()));
            // no exception, no side effects
        }

        @Test
        @DisplayName("get returns the registered future before completion")
        void getReturnsFutureBeforeCompletion() {
            var trainingId = UUID.randomUUID();
            var registered = registry.register(trainingId);

            assertThat(registry.get(trainingId)).isSameAs(registered);
            assertThat(registered).isNotDone();
        }

        @Test
        @DisplayName("get returns null for unregistered trainingId")
        void getReturnsNullForUnregistered() {
            assertThat(registry.get(UUID.randomUUID())).isNull();
        }
    }

    @Nested
    @DisplayName("completeExceptionally")
    class CompleteExceptionally {

        @Test
        @DisplayName("future fails with the provided exception")
        void futureFailsWithException() {
            var trainingId = UUID.randomUUID();
            var event = failedEvent(trainingId, "OOM killed");
            var cause = new TrainingFailedException(event);

            var future = registry.register(trainingId);
            registry.completeExceptionally(trainingId, cause);

            assertThat(future).isCompletedExceptionally();
            assertThatThrownBy(() -> future.get(1, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(TrainingFailedException.class)
                .satisfies(ex -> {
                    var tfe = (TrainingFailedException) ex.getCause();
                    assertThat(tfe.event().trainingId()).isEqualTo(trainingId);
                    assertThat(tfe.event().error()).isEqualTo("OOM killed");
                });
        }

        @Test
        @DisplayName("completeExceptionally keeps the entry in the registry (explicit remove needed)")
        void keepsEntry() {
            var trainingId = UUID.randomUUID();
            registry.register(trainingId);
            registry.completeExceptionally(trainingId, new RuntimeException("boom"));

            assertThat(registry.get(trainingId)).isNotNull().isCompletedExceptionally();
            registry.remove(trainingId);
            assertThat(registry.get(trainingId)).isNull();
        }

        @Test
        @DisplayName("completeExceptionally on unknown trainingId is a no-op")
        void unknownIdIsNoop() {
            registry.completeExceptionally(UUID.randomUUID(), new RuntimeException("boom"));
        }
    }

    @Nested
    @DisplayName("remove")
    class Remove {

        @Test
        @DisplayName("removes a registered future without completing it")
        void removesWithoutCompleting() {
            var trainingId = UUID.randomUUID();
            var future = registry.register(trainingId);

            registry.remove(trainingId);

            assertThat(registry.get(trainingId)).isNull();
            assertThat(future).isNotDone();
        }
    }

    @Nested
    @DisplayName("concurrent access")
    class ConcurrentAccess {

        @Test
        @DisplayName("register and complete from different threads")
        void registerAndCompleteFromDifferentThreads() throws Exception {
            var trainingId = UUID.randomUUID();
            var event = successEvent(trainingId);
            var future = registry.register(trainingId);

            var completer = CompletableFuture.runAsync(() -> {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                registry.complete(trainingId, event);
            });

            var result = future.get(2, TimeUnit.SECONDS);
            completer.get(1, TimeUnit.SECONDS);

            assertThat(result).isSameAs(event);
        }
    }
}
