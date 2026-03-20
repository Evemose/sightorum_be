package com.rorm.ml.stream;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Redis-backed rendezvous point for matching awakeable registrations with job events.
 * <p>
 * Whichever side arrives first stores its data; the second side finds it and completes
 * the handoff. A double-check in {@link #eventArrived} closes the TOCTOU race window.
 * Both sides survive arbitrary JVM crash sequences because Redis is the source of truth.
 */
@RequiredArgsConstructor
public class DurableRendezvous {

    private static final Duration TTL = Duration.ofDays(7);
    private static final String AWK_PREFIX = "durable:awk:";
    private static final String EVT_PREFIX = "durable:evt:";

    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * Store an awakeable registration. If the event already arrived, returns it immediately.
     */
    public Optional<JobEvent> register(UUID jobId, String awakeableId) {
        redisTemplate.opsForValue().set(awkKey(jobId), awakeableId, TTL);

        var early = redisTemplate.opsForValue().getAndDelete(evtKey(jobId));
        if (early instanceof JobEvent event) {
            redisTemplate.delete(awkKey(jobId));
            return Optional.of(event);
        }
        return Optional.empty();
    }

    private static String awkKey(UUID jobId) {
        return AWK_PREFIX + jobId;
    }

    private static String evtKey(UUID jobId) {
        return EVT_PREFIX + jobId;
    }

    /**
     * Deliver a job event. If an awakeable was already registered, returns its ID immediately.
     * Otherwise buffers the event for a later {@link #register} call.
     */
    public Optional<String> eventArrived(UUID jobId, JobEvent event) {
        var existing = redisTemplate.opsForValue().getAndDelete(awkKey(jobId));
        if (existing instanceof String awakeableId) {
            return Optional.of(awakeableId);
        }

        // Buffer event for later registration
        redisTemplate.opsForValue().set(evtKey(jobId), event, TTL);

        // Double-check: registration might have arrived between getAndDelete and set
        existing = redisTemplate.opsForValue().getAndDelete(awkKey(jobId));
        if (existing instanceof String awakeableId) {
            redisTemplate.delete(evtKey(jobId));
            return Optional.of(awakeableId);
        }

        return Optional.empty();
    }
}
