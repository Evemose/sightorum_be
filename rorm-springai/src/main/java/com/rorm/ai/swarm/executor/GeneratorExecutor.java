package com.rorm.ai.swarm.executor;

import com.rorm.ai.swarm.DurableSwarmConfig;
import com.rorm.ai.swarm.StepOutput;
import com.rorm.ai.swarm.dto.HypothesisGenerationDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component("generatorExecutor")
@RequiredArgsConstructor
public class GeneratorExecutor {

    private final DurableSwarmConfig config;
    private final StepExecutorSupport support;

    public StepOutput<HypothesisGenerationDTO> execute(StepExecutionInput input) {
        return support.execute(input, config.generator(), HypothesisGenerationDTO.class);
    }
}
