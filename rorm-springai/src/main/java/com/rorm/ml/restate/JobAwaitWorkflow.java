package com.rorm.ml.restate;

import com.rorm.ml.stream.JobEvent;
import dev.restate.sdk.WorkflowContext;
import dev.restate.sdk.annotation.Workflow;
import dev.restate.sdk.springboot.RestateWorkflow;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * Restate workflow that durably awaits a single ML job's completion.
 * <p>
 * Keyed by {@code jobId.toString()}. Creates an awakeable, registers it in {@link AwakeableRegistry}
 * so {@link RestateJobCompletionHandler} can resolve it when the Redis event arrives.
 * <p>
 * Input is only {@code String} (the job ID) — all context is reconstructed from persistent stores,
 * never captured from the caller.
 * <p>
 * Uses Spring Boot's {@code @RestateWorkflow} for auto-discovery. The Spring Boot starter
 * auto-configures the HTTP endpoint and uses Spring's ObjectMapper for serialization.
 */
@RestateWorkflow
@ConditionalOnProperty(name = "rorm.ml.durable-execution", havingValue = "true")
public class JobAwaitWorkflow {

    private final AwakeableRegistry awakeableRegistry;

    public JobAwaitWorkflow(AwakeableRegistry awakeableRegistry) {
        this.awakeableRegistry = awakeableRegistry;
    }

    @Workflow
    public JobEvent run(WorkflowContext ctx, String jobId) {
        var awakeable = ctx.awakeable(JobEvent.class);

        // Journaled side-effect: register awakeable ID so the Redis listener can resolve it.
        // On replay after crash, this re-executes deterministically with the same awakeable ID.
        ctx.run("register-awakeable", () ->
            awakeableRegistry.register(java.util.UUID.fromString(jobId), awakeable.id())
        );

        // Durable suspension point — survives JVM crashes.
        return awakeable.await();
    }
}
