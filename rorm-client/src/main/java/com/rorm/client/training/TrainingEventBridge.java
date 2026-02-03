package com.rorm.client.training;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rorm.ml.stream.TrainingEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Bridges training events from Redis to SSE subscribers.
 * Listens to the same Redis stream as TrainingStreamListener but forwards events to HTTP clients.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TrainingEventBridge implements StreamListener<String, MapRecord<String, String, String>> {

    private final TrainingProgressPublisher progressPublisher;
    private final ObjectMapper objectMapper;

    @Override
    public void onMessage(MapRecord<String, String, String> message) {
        try {
            var event = parseEvent(message.getValue());

            // Only forward if there are subscribers
            if (progressPublisher.hasSubscribers(event.trainingId())) {
                progressPublisher.publish(event);
            }
        } catch (Exception e) {
            log.error("Failed to process training event for SSE: {}", e.getMessage());
        }
    }

    private TrainingEvent parseEvent(Map<String, String> data) throws Exception {
        var payload = data.get("payload");
        if (payload != null) {
            return objectMapper.readValue(payload, TrainingEvent.class);
        }

        return objectMapper.convertValue(data, TrainingEvent.class);
    }
}
