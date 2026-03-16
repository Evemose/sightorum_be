package com.rorm.ml.stream;

import com.rorm.ml.peristence.MLJobInfo;

/**
 * Routes job completion events to the appropriate wakeup mechanism.
 * <p>
 * In-memory mode: completes a {@link java.util.concurrent.CompletableFuture}.
 * Restate mode: resolves a Restate awakeable.
 */
public interface JobCompletionHandler {
    void onJobSuccess(MLJobInfo jobInfo, JobEvent event);

    void onJobFailure(MLJobInfo jobInfo, JobEvent event);

    void onJobProgress(MLJobInfo jobInfo, JobEvent event);
}
