package com.rorm.ai.swarm.executor;

import com.rorm.ai.swarm.DurableSwarmConfig;
import com.rorm.ai.swarm.StepOutput;
import com.rorm.ai.swarm.dto.DomainResearchDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component("domainResearcherExecutor")
@RequiredArgsConstructor
public class DomainResearcherExecutor {

    private final DurableSwarmConfig config;
    private final StepExecutorSupport support;

    public StepOutput<DomainResearchDTO> execute(StepExecutionInput input) {
        return support.execute(input, config.domainResearcher(), DomainResearchDTO.class);
    }
}
