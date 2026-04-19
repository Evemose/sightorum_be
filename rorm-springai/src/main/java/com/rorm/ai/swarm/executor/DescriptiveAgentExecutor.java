package com.rorm.ai.swarm.executor;

import com.rorm.ai.swarm.DurableSwarmConfig;
import com.rorm.ai.swarm.StepOutput;
import com.rorm.viz.dto.Digest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component("descriptiveAgentExecutor")
@RequiredArgsConstructor
public class DescriptiveAgentExecutor {

    private final DurableSwarmConfig config;
    private final StepExecutorSupport support;

    public StepOutput<Digest> execute(StepExecutionInput input) {
        return support.execute(input, config.descriptiveAgent(), Digest.class);
    }
}
