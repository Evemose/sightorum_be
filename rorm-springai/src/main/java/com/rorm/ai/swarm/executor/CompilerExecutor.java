package com.rorm.ai.swarm.executor;

import com.rorm.ai.swarm.DurableSwarmConfig;
import com.rorm.ai.swarm.StepOutput;
import com.rorm.ml.dto.PipelineSpecRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component("compilerExecutor")
@RequiredArgsConstructor
public class CompilerExecutor {

    private final DurableSwarmConfig config;
    private final StepExecutorSupport support;

    public StepOutput<PipelineSpecRequest> execute(StepExecutionInput input) {
        return support.execute(input, config.executorCompiler(), PipelineSpecRequest.class);
    }
}
