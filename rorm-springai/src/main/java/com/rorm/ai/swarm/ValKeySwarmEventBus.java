package com.rorm.ai.swarm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.publisher.Sinks.EmitResult;
import reactor.util.concurrent.Queues;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.LockSupport;

/**
 * Valkey (Redis) Stream-backed event bus. Each run gets its own stream; each
 * subscriber owns a virtual-thread XREAD pump that starts at cursor
 * {@code 0} and pushes into a unicast sink — giving every subscriber an
 * independent full replay without consumer groups.
 * <p>
 * Tunables are constructor-injected so deployments can tune stream TTL,
 * batch size, sink capacity, XREAD block timeout, and backpressure park
 * without code changes.
 */
@Slf4j
@RequiredArgsConstructor
public class ValKeySwarmEventBus implements SwarmEventBus, DisposableBean {

    private static final String STREAM_PREFIX = "swarm:events:";
    private static final String DEDUP_PREFIX = "swarm:events:seen:";
    private static final String PAYLOAD_FIELD = "payload";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Duration streamTtl;
    private final int batchSize;
    private final int sinkCapacity;
    private final Duration xreadBlock;
    private final Duration backpressurePark;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @Override
    public Flux<SwarmStreamEvent> subscribe(String runId) {
        Sinks.Many<SwarmStreamEvent> sink = Sinks.many().unicast()
            .onBackpressureBuffer(Queues.<SwarmStreamEvent>get(sinkCapacity).get());
        var task = executor.submit(() -> pumpStream(runId, sink));
        return sink.asFlux().doFinally(_ -> task.cancel(true));
    }

    @SuppressWarnings("unchecked")
    private void pumpStream(String runId, Sinks.Many<SwarmStreamEvent> sink) {
        var streamKey = STREAM_PREFIX + runId;
        var readOpts = StreamReadOptions.empty().count(batchSize).block(xreadBlock);
        var cursor = "0";
        try {
            while (!Thread.currentThread().isInterrupted()) {
                var batch = redisTemplate.opsForStream().read(
                    readOpts, StreamOffset.create(streamKey, ReadOffset.from(cursor)));
                if (batch == null || batch.isEmpty()) {
                    continue;
                }
                for (var entry : batch) {
                    cursor = entry.getId().getValue();
                    if (emit(entry, sink)) {
                        return;
                    }
                }
            }
        } catch (Exception e) {
            if (!Thread.currentThread().isInterrupted()) {
                log.error("[swarm-bus] XREAD loop failed for run {}", runId, e);
            }
        }
    }

    private boolean emit(MapRecord<String, Object, Object> entry, Sinks.Many<SwarmStreamEvent> sink) {
        var payload = entry.getValue().get(PAYLOAD_FIELD);
        if (payload == null) {
            return false;
        }
        try {
            var event = objectMapper.readValue(payload.toString(), SwarmStreamEvent.class);
            if (event instanceof SwarmStreamEvent.RunCompleted) {
                sink.tryEmitComplete();
                return true;
            }
            while (sink.tryEmitNext(event) == EmitResult.FAIL_OVERFLOW) {
                LockSupport.parkNanos(backpressurePark.toNanos());
            }
        } catch (JsonProcessingException e) {
            log.warn("[swarm-bus] Failed to deserialize event", e);
        }
        return false;
    }

    @Override
    public void complete(String runId) {
        publish(runId, new SwarmStreamEvent.RunCompleted());
    }

    @Override
    public void publish(String runId, SwarmStreamEvent event) {
        var dedupKey = DEDUP_PREFIX + runId;
        var added = redisTemplate.opsForSet().add(dedupKey, event.dedupKey());
        if (added == null || added == 0) {
            return;
        }
        try {
            var json = objectMapper.writeValueAsString(event);
            var streamKey = STREAM_PREFIX + runId;
            redisTemplate.opsForStream().add(MapRecord.create(streamKey, Map.of(PAYLOAD_FIELD, json)));
        } catch (JsonProcessingException e) {
            log.error("[swarm-bus] Failed to serialize event for run {}", runId, e);
            redisTemplate.opsForSet().remove(dedupKey, event.dedupKey());
        }
    }

    @Override
    public void destroy() {
        executor.shutdownNow();
    }
}
