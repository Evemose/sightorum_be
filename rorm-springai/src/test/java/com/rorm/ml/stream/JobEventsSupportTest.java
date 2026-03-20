package com.rorm.ml.stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("JobEventsSupport")
class JobEventsSupportTest {

    private JobFutureRegistry registry;
    private JobEventsSupport support;

    private static JobEvent successEvent(UUID jobId) {
        return new JobEvent(
            jobId, JobEventType.JOB_SUCCESS, Instant.now(),
            0.0, "Done", Map.of("accuracy", 0.92), null, null, Map.of()
        );
    }

    private static JobEvent failedEvent(UUID jobId, String error) {
        return new JobEvent(
            jobId, JobEventType.JOB_FAILED, Instant.now(),
            0.0, null, null, error, "ERR_CONVERGENCE", Map.of()
        );
    }

    private static JobEvent progressEvent(UUID jobId, double progress) {
        return new JobEvent(
            jobId, JobEventType.JOB_PROGRESS, Instant.now(),
            progress, "Job in progress", null, null, null, Map.of()
        );
    }

    @BeforeEach
    void setUp() {
        registry = new JobFutureRegistry();
        support = new JobEventsSupport(registry);
    }

    @Nested
    @DisplayName("onJobSuccess")
    class OnJobSuccess {

        @Test
        @DisplayName("completes the registered future with the job event")
        void completesRegisteredFuture() throws Exception {
            var jobId = UUID.randomUUID();
            var future = registry.register(jobId);
            var event = successEvent(jobId);

            support.onJobSuccess(event);

            assertThat(future.get(1, TimeUnit.SECONDS))
                .satisfies(result -> {
                    assertThat(result.jobId()).isEqualTo(jobId);
                    assertThat(result.isSuccess()).isTrue();
                    assertThat(result.metrics()).containsEntry("accuracy", 0.92);
                });
        }

        @Test
        @DisplayName("is a no-op when no future was registered for the jobId")
        void noopWhenNoFutureRegistered() {
            var jobId = UUID.randomUUID();
            var event = successEvent(jobId);

            // should not throw
            support.onJobSuccess(event);
        }
    }

    @Nested
    @DisplayName("onJobFailure")
    class OnJobFailure {

        @Test
        @DisplayName("completes the future exceptionally with JobFailedException")
        void completesExceptionally() {
            var jobId = UUID.randomUUID();
            var future = registry.register(jobId);
            var event = failedEvent(jobId, "Convergence error");

            support.onJobFailure(event);

            assertThat(future).isCompletedExceptionally();
            assertThatThrownBy(() -> future.get(1, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(JobFailedException.class)
                .satisfies(ex -> {
                    var jfe = (JobFailedException) ex.getCause();
                    assertThat(jfe.event().jobId()).isEqualTo(jobId);
                    assertThat(jfe.event().error()).isEqualTo("Convergence error");
                });
        }
    }

    @Nested
    @DisplayName("onJobProgress")
    class OnJobProgress {

        @Test
        @DisplayName("does not complete or fail the registered future")
        void doesNotAffectFuture() {
            var jobId = UUID.randomUUID();
            var future = registry.register(jobId);
            var event = progressEvent(jobId, 0.5);

            support.onJobProgress(event);

            assertThat(future).isNotDone();
        }
    }
}
