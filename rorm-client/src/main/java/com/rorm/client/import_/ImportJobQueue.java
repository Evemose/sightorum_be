package com.rorm.client.import_;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rorm.client.import_.dto.ImportProgressEvent;
import com.rorm.client.import_.dto.SchemaOverrideDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class ImportJobQueue {

    private static final String JOBS_QUEUE = "rorm:import:jobs";
    private static final String PROGRESS_CHANNEL_PREFIX = "rorm:import:progress:";

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * Enqueues an import job for processing.
     */
    public void enqueue(ImportJobPayload payload) {
        try {
            var json = objectMapper.writeValueAsString(payload);
            redisTemplate.opsForList().rightPush(JOBS_QUEUE, json);
            log.info("Enqueued import job: {}", payload.jobId());
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize import job payload", e);
        }
    }

    /**
     * Dequeues the next import job for processing (blocking).
     */
    @Nullable
    public ImportJobPayload dequeue(Duration timeout) {
        var result = redisTemplate.opsForList().leftPop(JOBS_QUEUE, timeout);
        if (result == null) {
            return null;
        }

        try {
            var json = result instanceof String s ? s : objectMapper.writeValueAsString(result);
            return objectMapper.readValue(json, ImportJobPayload.class);
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize import job payload", e);
            return null;
        }
    }

    /**
     * Publishes a progress event for an import job.
     */
    public void publishProgress(ImportProgressEvent event) {
        var channel = PROGRESS_CHANNEL_PREFIX + event.jobId();
        try {
            var json = objectMapper.writeValueAsString(event);
            redisTemplate.convertAndSend(channel, json);
        } catch (JsonProcessingException e) {
            log.error("Failed to publish progress event", e);
        }
    }

    /**
     * Gets the progress channel name for a job.
     */
    public String getProgressChannel(UUID jobId) {
        return PROGRESS_CHANNEL_PREFIX + jobId;
    }

    /**
     * Payload for import jobs in the queue.
     * Uses DTOs for serialization (not domain objects).
     */
    public record ImportJobPayload(
        UUID jobId,
        String uploadDir,
        String targetSchema,
        int chunkSize,
        String listSeparator,
        Map<String, List<SchemaOverrideDTO>> overridesByRoot
    ) {}
}
