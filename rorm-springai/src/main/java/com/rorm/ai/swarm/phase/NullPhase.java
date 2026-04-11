package com.rorm.ai.swarm.phase;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rorm.ai.swarm.DurableSwarmConfig;
import com.rorm.ai.swarm.EventId;
import com.rorm.ai.swarm.SwarmEvent;
import com.rorm.ai.swarm.agents.DurableSwarmStep.StepOutput;
import com.rorm.ai.swarm.dto.ForensicDiagnosisDTO;
import com.rorm.ml.stream.JobEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

@Slf4j
@RequiredArgsConstructor
public class NullPhase {

    private final DurableSwarmConfig config;
    private final ObjectMapper objectMapper;

    public Output execute(EventId compilerId, String hypothesisId,
                          String specBlock, String domainRaw, JobEvent pipelineResult) {
        var ctx = SwarmScope.ctx();
        var anchorTag = SwarmScope.anchorTag();

        var fpId = EventId.child("forensic-pathologist",
            ctx.journal().randomUUID(),
            List.of(compilerId),
            Map.of("anchor", anchorTag, "hypothesis", hypothesisId));

        var fp = ctx.step(config.forensicPathologist(), ForensicDiagnosisDTO.class,
            SwarmEvent.ForensicPathologistStarted::new, SwarmEvent.ForensicPathologistFinished::new);

        log.info("[swarm] Forensic pathologist for {}", hypothesisId);
        var prompt = config.forensicPathologist().userPromptTemplate()
            .replace("{{HYPOTHESIS_SPEC}}", specBlock)
            .replace("{{DOMAIN_KNOWLEDGE}}", domainRaw)
            .replace("{{PIPELINE_OUTPUT}}", writeJson(pipelineResult));
        var result = fp.execute(fpId, prompt, ctx.events());

        return new Output(result);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return value.toString();
        }
    }

    public record Output(StepOutput<ForensicDiagnosisDTO> diagnosis) {}
}
