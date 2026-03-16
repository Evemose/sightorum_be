package com.rorm.ml.jobs;

import com.rorm.durable.DurableJob;
import com.rorm.durable.JobEntry;
import com.rorm.ml.MlTrainingService;
import com.rorm.ml.dto.ShapJobRequest;
import com.rorm.ml.stream.JobEvent;
import com.rorm.ml.stream.JobFutureRegistry;
import lombok.RequiredArgsConstructor;

import java.util.concurrent.TimeUnit;

@DurableJob
@RequiredArgsConstructor
public class ShapJob {

    private final MlTrainingService mlService;
    private final JobFutureRegistry resultRegistry;

    @JobEntry
    public JobEvent run(ShapJobRequest request, String schema) throws Exception {
        var jobId = mlService.submit(request);
        resultRegistry.register(jobId);
        return resultRegistry.await(jobId, 30, TimeUnit.MINUTES);
    }
}
