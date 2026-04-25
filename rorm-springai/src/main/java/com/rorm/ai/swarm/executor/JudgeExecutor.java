package com.rorm.ai.swarm.executor;

import com.rorm.ai.swarm.DurableSwarmConfig;
import com.rorm.ai.swarm.StepOutput;
import com.rorm.ai.swarm.dto.JudgeVerdictDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component("judgeExecutor")
@RequiredArgsConstructor
public class JudgeExecutor {

    private final DurableSwarmConfig config;
    private final StepExecutorSupport support;

    public StepOutput<JudgeVerdictDTO> execute(StepExecutionInput input) {
        return support.execute(input, config.judge(), JudgeVerdictDTO.class);
    }
}
