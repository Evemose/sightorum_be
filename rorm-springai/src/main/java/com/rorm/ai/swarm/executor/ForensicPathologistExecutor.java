package com.rorm.ai.swarm.executor;

import com.rorm.ai.swarm.DurableSwarmConfig;
import com.rorm.ai.swarm.StepOutput;
import com.rorm.ai.swarm.dto.ForensicDiagnosisDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component("forensicPathologistExecutor")
@RequiredArgsConstructor
public class ForensicPathologistExecutor {

    private final DurableSwarmConfig config;
    private final StepExecutorSupport support;

    public StepOutput<ForensicDiagnosisDTO> execute(StepExecutionInput input) {
        return support.execute(input, config.forensicPathologist(), ForensicDiagnosisDTO.class);
    }
}
