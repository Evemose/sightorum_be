package com.rorm.ml.stream;

import com.rorm.ml.peristence.MLJobInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@org.springframework.stereotype.Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
    name = "rorm.ml.durable-execution", havingValue = "false", matchIfMissing = true
)
@RequiredArgsConstructor
public class JobEventsSupport implements JobCompletionHandler {

    private final JobFutureRegistry registry;

    @Override
    public void onJobSuccess(MLJobInfo jobInfo, JobEvent event) {
        log.info("Completing future for job {}", jobInfo.jobId());
        registry.complete(jobInfo.jobId(), event);
    }

    @Override
    public void onJobFailure(MLJobInfo jobInfo, JobEvent event) {
        log.warn("Completing future exceptionally for job {}: {}", jobInfo.jobId(), event.error());
        registry.completeExceptionally(
            jobInfo.jobId(),
            new JobFailedException(event)
        );
    }

    @Override
    public void onJobProgress(MLJobInfo jobInfo, JobEvent event) {
        log.debug("Recording job progress for job {}: {}%",
            jobInfo.jobId(),
            String.format("%.1f", event.progress() * 100));
    }
}
