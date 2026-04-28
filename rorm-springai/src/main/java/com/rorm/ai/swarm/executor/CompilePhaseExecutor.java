package com.rorm.ai.swarm.executor;

import com.rorm.ai.swarm.phase.CompilePhase;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * JobSpec-callable wrapper around {@link CompilePhase}. Runs as its own Restate
 * sub-invocation so a parent fanout can dispatch one branch per hypothesis and
 * each branch owns its own journal — required because the compile phase
 * internally awaits an awakeable (ML pipeline completion) which cannot be
 * journaled inside a parent's {@code ctx.runAsync} closure.
 */
@Component("compilePhaseExecutor")
@RequiredArgsConstructor
public class CompilePhaseExecutor {

    private final CompilePhase compilePhase;

    public CompilePhase.Output execute(CompilePhaseExecutionInput input) {
        return compilePhase.run(input.hypoCtx(), input.runId());
    }
}
