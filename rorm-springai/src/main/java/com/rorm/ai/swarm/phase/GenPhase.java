package com.rorm.ai.swarm.phase;

import com.rorm.ai.chat.MemoryInclude;
import com.rorm.ai.swarm.DurableSwarmConfig;
import com.rorm.ai.swarm.EventId;
import com.rorm.ai.swarm.SwarmEvent;
import com.rorm.ai.swarm.agents.DurableSwarmStep.StepOutput;
import com.rorm.ai.swarm.dto.HypothesisGenerationDTO;
import com.rorm.ai.swarm.dto.ScepticReviewDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

@Slf4j
@RequiredArgsConstructor
public class GenPhase {

    private final DurableSwarmConfig config;

    public Output execute(String anchor, List<EventId> reconParents,
                          String scoutRaw, String domainRaw) {
        var ctx = SwarmScope.ctx();
        var anchorTag = SwarmScope.anchorTag();
        var tags = Map.of("anchor", anchorTag);
        var generatorId = EventId.child("generator",
            ctx.journal().randomUUID(), reconParents, tags);
        var scepticId = EventId.child("sceptic",
            ctx.journal().randomUUID(), List.of(generatorId), tags);
        var rebuttalId = EventId.child("rebuttal",
            ctx.journal().randomUUID(), List.of(scepticId), tags);

        var generator = ctx.step(config.generator(), HypothesisGenerationDTO.class,
            SwarmEvent.GeneratorStarted::new, SwarmEvent.GeneratorFinished::new);
        var sceptic = ctx.step(config.mechanicalSceptic(), ScepticReviewDTO.class,
            SwarmEvent.ScepticStarted::new, SwarmEvent.ScepticFinished::new);
        var rebuttal = ctx.step(config.generator(), HypothesisGenerationDTO.class,
            SwarmEvent.RebuttalStarted::new, SwarmEvent.RebuttalFinished::new);

        var chatId = "swarm-gen-" + ctx.journal().randomUUID();

        log.info("[swarm] Generator for anchor: {}", anchor.lines().findFirst().orElse(anchor));
        var generatorPrompt = config.generator().userPromptTemplate()
            .replace("{{USER_QUERY}}", ctx.input().userQuery())
            .replace("{{ANCHOR_ENTITY}}", anchor)
            .replace("{{CLUSTER_CONTEXT}}", scoutRaw)
            .replace("{{DOMAIN_RESEARCH}}", domainRaw);
        var generatorResult = generator.execute(generatorId, generatorPrompt,
            SwarmRunContext.withChatId(chatId), ctx.events());

        log.info("[swarm] Mechanical sceptic");
        var scepticPrompt = config.mechanicalSceptic().userPromptTemplate()
            .replace("{{GENERATOR_OUTPUT}}", generatorResult.rawResponse());
        var scepticResult = sceptic.execute(scepticId, scepticPrompt, ctx.events());

        log.info("[swarm] Generator rebuttal");
        var rebuttalPrompt = config.rebuttalPromptTemplate()
            .replace("{{SCEPTIC_FINDINGS}}", scepticResult.rawResponse());
        var rebuttalResult = rebuttal.execute(
            rebuttalId,
            rebuttalPrompt,
            b -> b.withChatId(chatId).withMemoryIncludes(
                MemoryInclude.TOOL_CALLS, MemoryInclude.TOOL_RESPONSES, MemoryInclude.THINKING
            ),
            ctx.events()
        );

        return new Output(chatId, generatorResult, scepticResult, rebuttalResult);
    }

    public record Output(
        String chatId,
        StepOutput<HypothesisGenerationDTO> generator,
        StepOutput<ScepticReviewDTO> sceptic,
        StepOutput<HypothesisGenerationDTO> rebuttal
    ) {}
}
