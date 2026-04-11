package com.rorm.ai.swarm.phase;

import com.rorm.ai.swarm.DurableSwarmConfig;
import com.rorm.ai.swarm.EventId;
import com.rorm.ai.swarm.SwarmEvent;
import com.rorm.ai.swarm.agents.DurableSwarmStep.StepOutput;
import com.rorm.ai.swarm.dto.DomainResearchDTO;
import com.rorm.ai.swarm.dto.ScoutAnalysisDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
@RequiredArgsConstructor
public class ReconPhase {

    private final DurableSwarmConfig config;

    @SuppressWarnings("unchecked")
    public Output execute() {
        var ctx = SwarmScope.ctx();
        var scoutId = EventId.root("scout", ctx.journal().randomUUID());
        var domainId = EventId.root("domain-researcher", ctx.journal().randomUUID());

        var scout = ctx.step(config.scout(), ScoutAnalysisDTO.class,
            SwarmEvent.DurableScoutStarted::new, SwarmEvent.DurableScoutFinished::new);
        var domain = ctx.step(config.domainResearcher(), DomainResearchDTO.class,
            SwarmEvent.DomainResearcherStarted::new, SwarmEvent.DomainResearcherFinished::new);

        log.info("[swarm] Starting scout + domain researcher");
        var results = ctx.journal().fanout("swarm:recon", StepOutput.class, List.of(
            () -> scout.execute(scoutId,
                config.scout().userPromptTemplate().replace("{{USER_QUERY}}", ctx.input().userQuery()),
                ctx.events()),
            () -> domain.execute(domainId,
                config.domainResearcher().userPromptTemplate().replace("{{USER_QUERY}}", ctx.input().userQuery()),
                ctx.events())
        ));

        return new Output(
            (StepOutput<ScoutAnalysisDTO>) results.get(0),
            (StepOutput<DomainResearchDTO>) results.get(1)
        );
    }

    public record Output(StepOutput<ScoutAnalysisDTO> scout, StepOutput<DomainResearchDTO> domain) {
        public List<EventId> parentIds() {
            return List.of(scout.id(), domain.id());
        }
    }
}
