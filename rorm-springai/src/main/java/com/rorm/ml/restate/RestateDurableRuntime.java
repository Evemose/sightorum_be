package com.rorm.ml.restate;

import com.rorm.durable.DurableRuntime;
import com.rorm.durable.JobSpec;
import dev.restate.client.Client;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class RestateDurableRuntime implements DurableRuntime {

    private final Client restateClient;

    @Override
    public Object submit(String sessionId, JobSpec spec) {
        return DurableJobServiceClient.fromClient(restateClient, sessionId)
            .execute(spec);
    }
}
