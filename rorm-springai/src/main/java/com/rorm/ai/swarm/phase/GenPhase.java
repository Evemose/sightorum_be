package com.rorm.ai.swarm.phase;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rorm.DurableRuntime;
import com.rorm.JobSpec;
import com.rorm.ai.chat.MemoryInclude;
import com.rorm.ai.swarm.ContentHash;
import com.rorm.ai.swarm.DurableSwarmConfig;
import com.rorm.ai.swarm.EventId;
import com.rorm.ai.swarm.PhaseScope;
import com.rorm.ai.swarm.StepOutput;
import com.rorm.ai.swarm.dto.HypothesisGenerationDTO;
import com.rorm.ai.swarm.dto.ScepticReviewDTO;
import com.rorm.ai.swarm.executor.StepExecutionInput;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;

/**
 * Generator → sceptic → rebuttal sequence for a single anchor. Each step is
 * an independent durable invocation; the rebuttal continues the generator's
 * conversation via {@code chatId}. {@code runId} is accepted in the public
 * API and rebound into {@link PhaseScope} for private helpers.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GenPhase {

    private static final TypeReference<StepOutput<HypothesisGenerationDTO>> HYPOTHESIS_REF = new TypeReference<>() {};
    private static final TypeReference<StepOutput<ScepticReviewDTO>> SCEPTIC_REF = new TypeReference<>() {};
    private static final EnumSet<MemoryInclude> REBUTTAL_MEMORY = EnumSet.of(
        MemoryInclude.TOOL_CALLS, MemoryInclude.TOOL_RESPONSES, MemoryInclude.THINKING);

    private final DurableRuntime runtime;
    private final ObjectMapper mapper;
    private final DurableSwarmConfig config;

    public Output run(AnchorContext anchor, String runId) {
        return ScopedValue.where(PhaseScope.RUN_ID, runId).call(() -> doRun(anchor));
    }

    private Output doRun(AnchorContext anchor) {
        var tags = Map.of("anchor", anchor.anchorTag());

        var genId = EventId.child("generator",
            ContentHash.of(Map.of(
                "kind", "generator",
                "schema", anchor.swarm().schema(),
                "query", anchor.swarm().userQuery(),
                "anchor", anchor.anchor(),
                "scoutContext", anchor.recon().scout().rawResponse(),
                "domainResearch", anchor.recon().domain().rawResponse())),
            anchor.recon().parentIds(), tags);
        var chatId = "swarm-gen-" + genId.token();
        var generator = runGenerator(anchor, genId, chatId);

        var sceId = EventId.child("sceptic",
            ContentHash.of(Map.of(
                "kind", "sceptic",
                "schema", anchor.swarm().schema(),
                "generatorOutput", generator.rawResponse())),
            List.of(genId), tags);
        var sceptic = runSceptic(anchor, sceId, generator.rawResponse());

        var rebId = EventId.child("rebuttal",
            ContentHash.of(Map.of(
                "kind", "rebuttal",
                "schema", anchor.swarm().schema(),
                "scepticFindings", sceptic.rawResponse())),
            List.of(sceId), tags);
        var rebuttal = runRebuttal(anchor, rebId, sceptic.rawResponse(), chatId);

        return new Output(chatId, generator, sceptic, rebuttal);
    }

    private StepOutput<HypothesisGenerationDTO> runGenerator(AnchorContext anchor, EventId id, String chatId) {
        log.info("[swarm] Generator for anchor: {}",
            anchor.anchor().lines().findFirst().orElse(anchor.anchor()));
        var input = new StepExecutionInput(
            id, generatorPrompt(anchor),
            anchor.swarm().schema(), anchor.swarm().modelSpace(),
            PhaseScope.runId(), chatId, null);
        return mapper.convertValue(submit("generatorExecutor", input), HYPOTHESIS_REF);
    }

    private StepOutput<ScepticReviewDTO> runSceptic(AnchorContext anchor, EventId id, String generatorRaw) {
        log.info("[swarm] Mechanical sceptic");
        var input = new StepExecutionInput(
            id, scepticPrompt(generatorRaw),
            anchor.swarm().schema(), anchor.swarm().modelSpace(), PhaseScope.runId());
        return mapper.convertValue(submit("scepticExecutor", input), SCEPTIC_REF);
    }

    private StepOutput<HypothesisGenerationDTO> runRebuttal(AnchorContext anchor, EventId id,
                                                            String scepticRaw, String chatId) {
        log.info("[swarm] Generator rebuttal");
        var input = new StepExecutionInput(
            id, rebuttalPrompt(scepticRaw),
            anchor.swarm().schema(), anchor.swarm().modelSpace(),
            PhaseScope.runId(), chatId, REBUTTAL_MEMORY);
        return mapper.convertValue(submit("generatorExecutor", input), HYPOTHESIS_REF);
    }

    private String generatorPrompt(AnchorContext anchor) {
        return config.generator().userPromptTemplate()
            .replace("{{USER_QUERY}}", anchor.swarm().userQuery())
            .replace("{{ANCHOR_ENTITY}}", anchor.anchor())
            .replace("{{CLUSTER_CONTEXT}}", anchor.recon().scout().rawResponse())
            .replace("{{DOMAIN_RESEARCH}}", anchor.recon().domain().rawResponse());
    }

    private Object submit(String beanName, StepExecutionInput stepInput) {
        var sessionId = beanName + "-" + stepInput.eventId().token();
        return runtime.submit(sessionId, new JobSpec(
            beanName, "execute",
            new Object[]{stepInput},
            new String[]{StepExecutionInput.class.getName()}
        ));
    }

    private String scepticPrompt(String generatorRaw) {
        return config.mechanicalSceptic().userPromptTemplate()
            .replace("{{GENERATOR_OUTPUT}}", generatorRaw);
    }

    private String rebuttalPrompt(String scepticRaw) {
        return config.rebuttalPromptTemplate().replace("{{SCEPTIC_FINDINGS}}", scepticRaw);
    }

    public record Output(
        String chatId,
        StepOutput<HypothesisGenerationDTO> generator,
        StepOutput<ScepticReviewDTO> sceptic,
        StepOutput<HypothesisGenerationDTO> rebuttal
    ) {}
}
