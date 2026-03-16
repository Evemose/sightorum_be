package com.rorm.durable;

import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Function;

public class InMemoryDurableJobRuntime implements DurableJobRuntime {

    private final Function<JobSpec, Object> executor;
    private final ConcurrentMap<UUID, CompletableFuture<Object>> futures = new ConcurrentHashMap<>();
    private final ExecutorService threadPool = Executors.newVirtualThreadPerTaskExecutor();

    public InMemoryDurableJobRuntime(Function<JobSpec, Object> executor) {
        this.executor = executor;
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> Awaitable<T> submit(JobSpec spec) {
        var jobId = UUID.randomUUID();
        var future = CompletableFuture.supplyAsync(() -> executor.apply(spec), threadPool);
        futures.put(jobId, future);
        return new Awaitable<>(jobId, this);
    }

    @Override
    public Object getResult(UUID jobId) throws Exception {
        var future = futures.get(jobId);
        if (future == null) {
            throw new IllegalStateException("Unknown job: " + jobId);
        }
        return future.get();
    }

    @Override
    public Object getResult(UUID jobId, long timeout, TimeUnit unit) throws Exception {
        var future = futures.get(jobId);
        if (future == null) {
            throw new IllegalStateException("Unknown job: " + jobId);
        }
        return future.get(timeout, unit);
    }

    @Override
    public boolean isDone(UUID jobId) {
        var future = futures.get(jobId);
        return future != null && future.isDone();
    }
}
