package com.rorm.ai.swarm.executor;

import com.rorm.ai.swarm.DurableSwarmConfig;
import com.rorm.ai.swarm.StepOutput;
import com.rorm.ai.swarm.dto.ScoutAnalysisDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component("scoutExecutor")
@RequiredArgsConstructor
public class ScoutExecutor {

    private final DurableSwarmConfig config;
    private final StepExecutorSupport support;

    public StepOutput<ScoutAnalysisDTO> execute(StepExecutionInput input) {
        return support.execute(input, config.scout(), ScoutAnalysisDTO.class);
    }
}
