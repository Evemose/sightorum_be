package com.rorm.ai.swarm.executor;

import com.rorm.ai.swarm.DurableSwarmConfig;
import com.rorm.ai.swarm.StepOutput;
import com.rorm.ai.swarm.dto.SupervisorVerdictDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component("supervisorExecutor")
@RequiredArgsConstructor
public class SupervisorExecutor {

    private final DurableSwarmConfig config;
    private final StepExecutorSupport support;

    public StepOutput<SupervisorVerdictDTO> execute(StepExecutionInput input) {
        return support.execute(input, config.supervisor(), SupervisorVerdictDTO.class);
    }
}
