package com.rorm.ai.swarm.executor;

import com.rorm.ai.swarm.DurableSwarmConfig;
import com.rorm.ai.swarm.StepOutput;
import com.rorm.ai.swarm.dto.CompilerCorrectionDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component("compilerScepticExecutor")
@RequiredArgsConstructor
public class CompilerScepticExecutor {

    private final DurableSwarmConfig config;
    private final StepExecutorSupport support;

    public StepOutput<CompilerCorrectionDTO> execute(StepExecutionInput input) {
        return support.execute(input, config.compilerSceptic(), CompilerCorrectionDTO.class);
    }
}
