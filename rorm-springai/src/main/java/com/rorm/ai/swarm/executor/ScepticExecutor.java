package com.rorm.ai.swarm.executor;

import com.rorm.ai.swarm.DurableSwarmConfig;
import com.rorm.ai.swarm.StepOutput;
import com.rorm.ai.swarm.dto.ScepticReviewDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component("scepticExecutor")
@RequiredArgsConstructor
public class ScepticExecutor {

    private final DurableSwarmConfig config;
    private final StepExecutorSupport support;

    public StepOutput<ScepticReviewDTO> execute(StepExecutionInput input) {
        return support.execute(input, config.mechanicalSceptic(), ScepticReviewDTO.class);
    }
}
