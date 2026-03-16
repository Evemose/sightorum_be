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

@DisplayName("JobFutureRegistry")
class JobFutureRegistryTest {

    private final JobFutureRegistry registry = new JobFutureRegistry();

    private static JobEvent successEvent(UUID jobId) {
        return new JobEvent(
            jobId, JobEventType.JOB_SUCCESS, Instant.now(),
            0.0, "Model trained", Map.of("accuracy", 0.95), null, null, Map.of()
        );
    }

    private static JobEvent failedEvent(UUID jobId, String error) {
        return new JobEvent(
            jobId, JobEventType.JOB_FAILED, Instant.now(),
            0.0, null, null, error, "ERR_OOM", Map.of()
        );
    }

    @Nested
    @DisplayName("register and complete")
    class RegisterAndComplete {

        @Test
        @DisplayName("registered future completes with the provided event")
        void registeredFutureCompletesWithEvent() throws Exception {
            var jobId = UUID.randomUUID();
            var event = successEvent(jobId);

            var future = registry.register(jobId);
            registry.complete(jobId, event);

            assertThat(future.get(1, TimeUnit.SECONDS)).isSameAs(event);
        }

        @Test
        @DisplayName("complete keeps the entry in the registry (explicit remove needed)")
        void completeKeepsEntry() {
            var jobId = UUID.randomUUID();
            var event = successEvent(jobId);
            registry.register(jobId);
            registry.complete(jobId, event);

            assertThat(registry.get(jobId)).isNotNull().isDone();
            // Explicit remove cleans up
            registry.remove(jobId);
            assertThat(registry.get(jobId)).isNull();
        }

        @Test
        @DisplayName("complete on unknown jobId is a no-op")
        void completeUnknownIdIsNoop() {
            registry.complete(UUID.randomUUID(), successEvent(UUID.randomUUID()));
            // no exception, no side effects
        }

        @Test
        @DisplayName("get returns the registered future before completion")
        void getReturnsFutureBeforeCompletion() {
            var jobId = UUID.randomUUID();
            var registered = registry.register(jobId);

            assertThat(registry.get(jobId)).isSameAs(registered);
            assertThat(registered).isNotDone();
        }

        @Test
        @DisplayName("get returns null for unregistered jobId")
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
            var jobId = UUID.randomUUID();
            var event = failedEvent(jobId, "OOM killed");
            var cause = new JobFailedException(event);

            var future = registry.register(jobId);
            registry.completeExceptionally(jobId, cause);

            assertThat(future).isCompletedExceptionally();
            assertThatThrownBy(() -> future.get(1, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(JobFailedException.class)
                .satisfies(ex -> {
                    var jfe = (JobFailedException) ex.getCause();
                    assertThat(jfe.event().jobId()).isEqualTo(jobId);
                    assertThat(jfe.event().error()).isEqualTo("OOM killed");
                });
        }

        @Test
        @DisplayName("completeExceptionally keeps the entry in the registry (explicit remove needed)")
        void keepsEntry() {
            var jobId = UUID.randomUUID();
            registry.register(jobId);
            registry.completeExceptionally(jobId, new RuntimeException("boom"));

            assertThat(registry.get(jobId)).isNotNull().isCompletedExceptionally();
            registry.remove(jobId);
            assertThat(registry.get(jobId)).isNull();
        }

        @Test
        @DisplayName("completeExceptionally on unknown jobId is a no-op")
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
            var jobId = UUID.randomUUID();
            var future = registry.register(jobId);

            registry.remove(jobId);

            assertThat(registry.get(jobId)).isNull();
            assertThat(future).isNotDone();
        }
    }

    @Nested
    @DisplayName("concurrent access")
    class ConcurrentAccess {

        @Test
        @DisplayName("register and complete from different threads")
        void registerAndCompleteFromDifferentThreads() throws Exception {
            var jobId = UUID.randomUUID();
            var event = successEvent(jobId);
            var future = registry.register(jobId);

            var completer = CompletableFuture.runAsync(() -> {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                registry.complete(jobId, event);
            });

            var result = future.get(2, TimeUnit.SECONDS);
            completer.get(1, TimeUnit.SECONDS);

            assertThat(result).isSameAs(event);
        }
    }
}
