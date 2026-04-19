package com.rorm.ai.swarm.phase;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rorm.DurableRuntime;
import com.rorm.JobSpec;
import com.rorm.StepJournal;
import com.rorm.ai.swarm.ContentHash;
import com.rorm.ai.swarm.DurableSwarmConfig;
import com.rorm.ai.swarm.EventId;
import com.rorm.ai.swarm.PhaseScope;
import com.rorm.ai.swarm.StepOutput;
import com.rorm.ai.swarm.SwarmInput;
import com.rorm.ai.swarm.dto.DomainResearchDTO;
import com.rorm.ai.swarm.dto.ScoutAnalysisDTO;
import com.rorm.ai.swarm.executor.StepExecutionInput;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Recon phase: fans out scout and domain-researcher executors as independent
 * durable invocations. {@code runId} is accepted as a parameter and rebound
 * into {@link PhaseScope} so private helpers read it without threading.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReconPhase {

    private static final TypeReference<StepOutput<ScoutAnalysisDTO>> SCOUT_REF = new TypeReference<>() {};
    private static final TypeReference<StepOutput<DomainResearchDTO>> DOMAIN_REF = new TypeReference<>() {};

    private final DurableRuntime runtime;
    private final ObjectMapper mapper;
    private final DurableSwarmConfig config;

    public Output run(SwarmInput input, String runId) {
        return ScopedValue.where(PhaseScope.RUN_ID, runId).call(() -> doRun(input));
    }

    private Output doRun(SwarmInput input) {
        var scoutInput = inputFor(input, "scout", config.scout().userPromptTemplate());
        var domainInput = inputFor(input, "domain-researcher", config.domainResearcher().userPromptTemplate());

        log.info("[swarm] Starting scout + domain researcher");
        var results = StepJournal.current().fanout("swarm:recon", Object.class, List.of(
            submit("scoutExecutor", scoutInput),
            submit("domainResearcherExecutor", domainInput)
        ));
        return new Output(
            mapper.convertValue(results.get(0), SCOUT_REF),
            mapper.convertValue(results.get(1), DOMAIN_REF)
        );
    }

    private StepExecutionInput inputFor(SwarmInput input, String kind, String template) {
        var id = EventId.root(kind, ContentHash.of(Map.of(
            "kind", kind,
            "schema", input.schema(),
            "query", input.userQuery())));
        var prompt = template.replace("{{USER_QUERY}}", input.userQuery());
        return new StepExecutionInput(id, prompt, input.schema(), input.modelSpace(), PhaseScope.runId());
    }

    private Supplier<Object> submit(String beanName, StepExecutionInput stepInput) {
        var sessionId = beanName + "-" + stepInput.eventId().token();
        return () -> runtime.submit(sessionId, new JobSpec(
            beanName, "execute",
            new Object[]{stepInput},
            new String[]{StepExecutionInput.class.getName()}
        ));
    }

    public record Output(StepOutput<ScoutAnalysisDTO> scout, StepOutput<DomainResearchDTO> domain) {
        public List<EventId> parentIds() {
            return List.of(scout.id(), domain.id());
        }
    }
}
