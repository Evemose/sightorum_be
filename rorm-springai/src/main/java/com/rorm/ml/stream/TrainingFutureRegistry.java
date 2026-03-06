package com.rorm.ml.stream;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@org.springframework.stereotype.Component
public class TrainingFutureRegistry {

    private final ConcurrentMap<UUID, CompletableFuture<TrainingEvent>> futures = new ConcurrentHashMap<>();

    public CompletableFuture<TrainingEvent> register(UUID trainingId) {
        var future = new CompletableFuture<TrainingEvent>();
        futures.put(trainingId, future);
        return future;
    }

    public void complete(UUID trainingId, TrainingEvent event) {
        var future = futures.get(trainingId);
        if (future != null) {
            future.complete(event);
        }
    }

    public void completeExceptionally(UUID trainingId, Throwable cause) {
        var future = futures.get(trainingId);
        if (future != null) {
            future.completeExceptionally(cause);
        }
    }

    public CompletableFuture<TrainingEvent> get(UUID trainingId) {
        return futures.get(trainingId);
    }

    public void remove(UUID trainingId) {
        futures.remove(trainingId);
    }
}
