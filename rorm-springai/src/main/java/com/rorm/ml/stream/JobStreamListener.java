package com.rorm.ml.stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rorm.ml.RormMlProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class JobStreamListener implements StreamListener<String, MapRecord<String, String, String>> {

    private final JobCompletionHandler completionHandler;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redisTemplate;
    private final RormMlProperties properties;
    private final WorkerTaskRegistry workerTaskRegistry;

    @Override
    @Retryable(retryFor = {Exception.class}, backoff = @Backoff(delay = 1000, multiplier = 2))
    public void onMessage(MapRecord<String, String, String> message) {
        log.debug("Received stream message: {}", message.getId());

        try {
            var event = parseEvent(message.getValue());
            handleEvent(event);
            redisTemplate.opsForStream().acknowledge(
                message.getStream(), properties.consumerGroup(), message.getId());
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

        var parsed = new HashMap<String, Object>(data);
        for (var field : List.of("metadata", "metrics")) {
            var raw = data.get(field);
            if (raw != null && (raw.startsWith("{") || raw.startsWith("["))) {
                parsed.put(field, objectMapper.readValue(raw, Object.class));
            }
        }

        return objectMapper.convertValue(parsed, JobEvent.class);
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
        workerTaskRegistry.register(event.jobId(), event.workerTaskArn());
    }

    private void handleProgress(JobEvent event) {
        log.debug("Job progress: {} - {}%",
            event.jobId(),
            String.format("%.1f", event.progress() * 100));

        completionHandler.onJobProgress(event);
    }

    private void handleSuccess(JobEvent event) {
        log.info("Job succeeded: {} - {}", event.jobId(), event.message());

        workerTaskRegistry.clear(event.jobId());
        completionHandler.onJobSuccess(event);
    }

    private void handleFailed(JobEvent event) {
        log.warn("Job failed: {} - {} ({})",
            event.jobId(), event.error(), event.errorCode());

        workerTaskRegistry.clear(event.jobId());
        completionHandler.onJobFailure(event);
    }
}
