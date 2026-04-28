package com.rorm.ml.restate;

import com.rorm.DurableFuture;
import com.rorm.ml.stream.*;
import dev.restate.client.Client;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

@Slf4j
@RequiredArgsConstructor
public class RestateJobCompletionHandler implements JobCompletionHandler {

    private final Client restateClient;
    private final JobFutureRegistry fallbackRegistry;
    private final DurableRendezvous rendezvous;

    @Override
    public void register(UUID jobId, DurableFuture<JobEvent> future) {
        rendezvous.register(jobId, future.id())
            .ifPresent(event -> {
                log.info("Resolving awakeable for job {} (event arrived before registration)", jobId);
                dispatch(future.id(), event);
            });
    }

    private void dispatch(String awakeableId, JobEvent event) {
        restateClient.awakeableHandle(awakeableId).resolve(JobEvent.class, event);
    }

    @Override
    public void onJobSuccess(JobEvent event) {
        resolveEvent(event);
    }

    private void resolveEvent(JobEvent event) {
        var awakeableId = rendezvous.eventArrived(event.jobId(), event);
        if (awakeableId.isPresent()) {
            log.info("Resolving awakeable {} for job {}", awakeableId.get(), event.jobId());
            dispatch(awakeableId.get(), event);
            return;
        }
        // Not a durable-registered job — try fallback for non-durable callers
        if (event.isSuccess()) {
            fallbackRegistry.complete(event.jobId(), event);
        } else {
            fallbackRegistry.completeExceptionally(event.jobId(), new JobFailedException(event));
        }
    }

    @Override
    public void onJobFailure(JobEvent event) {
        resolveEvent(event);
    }

    @Override
    public void onJobProgress(JobEvent event) {
        log.debug("Job progress for {}: {}%",
            event.jobId(),
            String.format("%.1f", event.progress() * 100));
    }
}
