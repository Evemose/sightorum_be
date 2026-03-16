package com.rorm.ml.restate;

import com.rorm.durable.Awaitable;
import com.rorm.durable.DurableJobRuntime;
import com.rorm.durable.JobSpec;
import dev.restate.client.Client;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@RequiredArgsConstructor
public class RestateDurableJobRuntime implements DurableJobRuntime {

    private final Client restateClient;

    @SuppressWarnings("unchecked")
    @Override
    public <T> Awaitable<T> submit(JobSpec spec) {
        var jobId = UUID.randomUUID();
        JobAwaitWorkflowClient.fromClient(restateClient, jobId.toString())
            .submit(spec);
        return new Awaitable<>(jobId, this);
    }

    @Override
    public Object getResult(UUID jobId) throws Exception {
        return JobAwaitWorkflowClient.fromClient(restateClient, jobId.toString())
            .workflowHandle()
            .attach()
            .response();
    }

    @Override
    public Object getResult(UUID jobId, long timeout, TimeUnit unit) throws Exception {
        try {
            return JobAwaitWorkflowClient.fromClient(restateClient, jobId.toString())
                .workflowHandle()
                .attachAsync()
                .get(timeout, unit)
                .response();
        } catch (TimeoutException e) {
            return null;
        }
    }

    @Override
    public boolean isDone(UUID jobId) {
        try {
            return JobAwaitWorkflowClient.fromClient(restateClient, jobId.toString())
                .workflowHandle()
                .getOutput()
                .response()
                .isReady();
        } catch (Exception e) {
            return false;
        }
    }
}
