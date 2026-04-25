package com.rorm.ai.swarm.executor;

import com.rorm.ai.swarm.DurableSwarmConfig;
import com.rorm.ai.swarm.StepOutput;
import com.rorm.ai.swarm.dto.StandoffArgumentDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component("prosecutorExecutor")
@RequiredArgsConstructor
public class ProsecutorExecutor {

    private final DurableSwarmConfig config;
    private final StepExecutorSupport support;

    public StepOutput<StandoffArgumentDTO> execute(StepExecutionInput input) {
        return support.execute(input, config.prosecutor(), StandoffArgumentDTO.class);
    }
}
