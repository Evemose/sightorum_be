package com.rorm.ml.stream;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Slf4j
@Component
public class WorkerTaskRegistry {

    private final ConcurrentMap<UUID, Entry> entries = new ConcurrentHashMap<>();

    public void register(UUID jobId, String workerTaskArn) {
        if (workerTaskArn == null || workerTaskArn.isBlank()) {
            return;
        }
        entries.put(jobId, new Entry(workerTaskArn, Instant.now()));
        log.debug("registered worker task for job {}: {}", jobId, workerTaskArn);
    }

    public void clear(UUID jobId) {
        entries.remove(jobId);
    }

    public Map<UUID, Entry> snapshot() {
        return Map.copyOf(entries);
    }

    public record Entry(String workerTaskArn, Instant registeredAt) {}
}
