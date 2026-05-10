package com.rorm.ai.swarm.executor;

import com.rorm.ai.swarm.DurableSwarmConfig;
import com.rorm.ai.swarm.StepOutput;
import com.rorm.ai.swarm.agents.CompilerSecondaryAgent;
import com.rorm.ai.swarm.dto.CompilerResultDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component("compilerExecutor")
@RequiredArgsConstructor
public class CompilerExecutor {

    private final DurableSwarmConfig config;
    private final StepExecutorSupport support;
    private final CompilerSecondaryAgent secondaryAgent;

    public StepOutput<CompilerResultDTO> execute(StepExecutionInput input) {
        return support.execute(input, config.executorCompiler(), secondaryAgent);
    }
}
