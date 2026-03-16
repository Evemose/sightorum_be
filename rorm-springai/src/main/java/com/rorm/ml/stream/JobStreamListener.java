package com.rorm.ml.stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rorm.ml.peristence.MLJobInfo;
import com.rorm.ml.peristence.MLJobMetadataStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.stream.StreamListener;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

@Slf4j
@org.springframework.stereotype.Component
@RequiredArgsConstructor
public class JobStreamListener implements StreamListener<String, MapRecord<String, String, String>> {

    private final JobEventsSupport completionHandler;
    private final ObjectMapper objectMapper;
    private final MLJobMetadataStore jobMetadataStore;

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

    private JobEvent parseEvent(Map<String, String> data) throws JsonProcessingException {
        var payload = data.get("payload");
        if (payload != null) {
            return objectMapper.readValue(payload, JobEvent.class);
        }

        var jobIdStr = data.get("job_id");
        if (jobIdStr == null) {
            throw new IllegalArgumentException("Missing job_id in message");
        }

        data = fillDefaults(data);

        return objectMapper.convertValue(data, JobEvent.class);
    }

    private void handleEvent(JobEvent event) {
        log.info("Processing job event: {} for job {}",
            event.eventType(), event.jobId());

        switch (event.eventType()) {
            case JOB_STARTED -> handleStarted(event);
            case JOB_PROGRESS -> handleProgress(event);
            case JOB_SUCCESS -> handleSuccess(event);
            case JOB_FAILED, VALIDATION_FAILED -> handleFailed(event);
        }
    }

    private Map<String, String> fillDefaults(Map<String, String> data) {
        var newData = new HashMap<>(data);
        newData.putIfAbsent("timestamp", Instant.now().toString());
        return newData;
    }

    private void handleStarted(JobEvent event) {
        log.info("Job started: {} - {}", event.jobId(), event.message());
    }

    private void handleProgress(JobEvent event) {
        log.debug("Job progress: {} - {}%",
            event.jobId(),
            String.format("%.1f", event.progress() * 100));

        doWithJobInfo(event, jobInfo -> completionHandler.onJobProgress(jobInfo, event));
    }

    private void handleSuccess(JobEvent event) {
        log.info("Job succeeded: {} - {}", event.jobId(), event.message());

        doWithJobInfo(event, jobInfo -> completionHandler.onJobSuccess(jobInfo, event));
    }

    private void handleFailed(JobEvent event) {
        log.warn("Job failed: {} - {} ({})",
            event.jobId(), event.error(), event.errorCode());

        doWithJobInfo(event, jobInfo -> completionHandler.onJobFailure(jobInfo, event));
    }

    private void doWithJobInfo(JobEvent event, Consumer<MLJobInfo> consumer) {
        jobMetadataStore.findByJobId(event.jobId())
            .ifPresentOrElse(
                consumer,
                () -> log.debug("No job info found for id {}", event.jobId())
            );
    }
}
