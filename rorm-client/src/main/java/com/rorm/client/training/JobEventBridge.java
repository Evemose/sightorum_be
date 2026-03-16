package com.rorm.client.training;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rorm.ml.stream.JobEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Bridges job events from Redis to SSE subscribers.
 * Listens to the same Redis stream as JobStreamListener but forwards events to HTTP clients.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JobEventBridge implements StreamListener<String, MapRecord<String, String, String>> {

    private final JobProgressPublisher progressPublisher;
    private final ObjectMapper objectMapper;

    @Override
    public void onMessage(MapRecord<String, String, String> message) {
        try {
            var event = parseEvent(message.getValue());

            // Only forward if there are subscribers
            if (progressPublisher.hasSubscribers(event.jobId())) {
                progressPublisher.publish(event);
            }
        } catch (Exception e) {
            log.error("Failed to process job event for SSE: {}", e.getMessage());
        }
    }

    private JobEvent parseEvent(Map<String, String> data) throws Exception {
        var payload = data.get("payload");
        if (payload != null) {
            return objectMapper.readValue(payload, JobEvent.class);
        }

        return objectMapper.convertValue(data, JobEvent.class);
    }
}
