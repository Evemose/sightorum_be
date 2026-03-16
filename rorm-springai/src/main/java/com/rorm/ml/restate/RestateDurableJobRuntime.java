package com.rorm.ml.restate;

import com.rorm.durable.Awaitable;
import com.rorm.durable.DurableJobRuntime;
import com.rorm.durable.JobSpec;
import com.rorm.ml.jobs.JobExecutor;
import dev.restate.client.Client;
import dev.restate.client.SendResponse;
import lombok.RequiredArgsConstructor;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@RequiredArgsConstructor
public class RestateDurableJobRuntime implements DurableJobRuntime {

    private final Client restateClient;
    private final ConcurrentMap<UUID, String> invocationIds = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    @Override
    public <T> Awaitable<T> submit(JobSpec spec) {
        var jobId = UUID.randomUUID();
        var response = RestateJobServiceClient.fromClient(restateClient)
            .send()
            .execute(spec);
        invocationIds.put(jobId, response.invocationId());
        return (Awaitable<T>) new Awaitable<>(jobId, this);
    }

    @Override
    public Object getResult(UUID jobId) throws Exception {
        var invocationId = invocationIds.get(jobId);
        if (invocationId == null) throw new IllegalStateException("Unknown job: " + jobId);
        return restateClient.invocationHandle(invocationId, Object.class)
            .attach()
            .response();
    }

    @Override
    public Object getResult(UUID jobId, long timeout, TimeUnit unit) throws Exception {
        var invocationId = invocationIds.get(jobId);
        if (invocationId == null) throw new IllegalStateException("Unknown job: " + jobId);
        try {
            return restateClient.invocationHandle(invocationId, Object.class)
                .attachAsync()
                .get(timeout, unit)
                .response();
        } catch (TimeoutException e) {
            return null;
        }
    }

    @Override
    public boolean isDone(UUID jobId) {
        var invocationId = invocationIds.get(jobId);
        if (invocationId == null) return false;
        try {
            var output = restateClient.invocationHandle(invocationId, Object.class)
                .getOutput()
                .response();
            return output.isReady();
        } catch (Exception e) {
            return false;
        }
    }
}
