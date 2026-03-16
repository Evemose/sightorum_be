package com.rorm.ml.restate;

import com.rorm.durable.JobSpec;
import com.rorm.ml.jobs.JobExecutor;
import dev.restate.sdk.Context;
import dev.restate.sdk.annotation.Handler;
import dev.restate.sdk.springboot.RestateService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@RestateService
@dev.restate.sdk.annotation.Name("DurableJobService")
@ConditionalOnProperty(name = "rorm.ml.durable-execution", havingValue = "true")
@RequiredArgsConstructor
public class RestateJobService {

    private final JobExecutor jobExecutor;

    @Handler
    public Object execute(Context ctx, JobSpec spec) {
        return ctx.run("execute-job", Object.class, () -> jobExecutor.execute(spec));
    }
}
