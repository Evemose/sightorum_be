package com.rorm.ml.stream;

import com.rorm.ml.peristence.MLJobInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@org.springframework.stereotype.Component
@RequiredArgsConstructor
public class JobEventsSupport {

    private final JobFutureRegistry registry;

    public void onJobSuccess(MLJobInfo jobInfo, JobEvent event) {
        log.info("Completing future for job {}", jobInfo.jobId());
        registry.complete(jobInfo.jobId(), event);
    }

    public void onJobFailure(MLJobInfo jobInfo, JobEvent event) {
        log.warn("Completing future exceptionally for job {}: {}", jobInfo.jobId(), event.error());
        registry.completeExceptionally(
            jobInfo.jobId(),
            new JobFailedException(event)
        );
    }

    public void onJobProgress(MLJobInfo jobInfo, JobEvent event) {
        log.debug("Recording job progress for job {}: {}%",
            jobInfo.jobId(),
            String.format("%.1f", event.progress() * 100));
    }
}
