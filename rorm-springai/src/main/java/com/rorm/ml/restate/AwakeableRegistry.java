package com.rorm.ml.restate;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory mapping from jobId to Restate awakeable ID.
 * <p>
 * Populated by the workflow's {@code ctx.run()} side effect (replayed deterministically on crash recovery).
 * Consumed by {@link RestateJobCompletionHandler} when Redis events arrive.
 */
public class AwakeableRegistry {

    private final ConcurrentMap<UUID, String> awakeableIds = new ConcurrentHashMap<>();

    public void register(UUID jobId, String awakeableId) {
        awakeableIds.put(jobId, awakeableId);
    }

    public Optional<String> get(UUID jobId) {
        return Optional.ofNullable(awakeableIds.get(jobId));
    }

    public Optional<String> getAndRemove(UUID jobId) {
        return Optional.ofNullable(awakeableIds.remove(jobId));
    }
}
