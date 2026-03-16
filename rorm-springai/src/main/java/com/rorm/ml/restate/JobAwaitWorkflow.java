package com.rorm.ml.restate;

import com.rorm.durable.JobSpec;
import com.rorm.ml.jobs.JobExecutor;
import dev.restate.sdk.WorkflowContext;
import dev.restate.sdk.annotation.Workflow;
import dev.restate.sdk.springboot.RestateWorkflow;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@RestateWorkflow
@ConditionalOnProperty(name = "rorm.ml.durable-execution", havingValue = "true")
@RequiredArgsConstructor
public class JobAwaitWorkflow {

    private final JobExecutor jobExecutor;

    @Workflow
    public Object run(WorkflowContext ctx, JobSpec spec) {
        return ctx.run("execute-job", Object.class, () -> {
            try {
                return jobExecutor.execute(spec);
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }
        });
    }
}
