package com.rorm.ml.stream;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class JobEventsSupport {

    private final JobFutureRegistry registry;

    public void onJobSuccess(JobEvent event) {
        log.info("Completing future for job {}", event.jobId());
        registry.complete(event.jobId(), event);
    }

    public void onJobFailure(JobEvent event) {
        log.warn("Completing future exceptionally for job {}: {}", event.jobId(), event.error());
        registry.completeExceptionally(
            event.jobId(),
            new JobFailedException(event)
        );
    }

    public void onJobProgress(JobEvent event) {
        log.debug("Recording job progress for job {}: {}%",
            event.jobId(),
            String.format("%.1f", event.progress() * 100));
    }
}
