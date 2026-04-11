package com.rorm.ai.swarm.phase;

import com.rorm.ai.swarm.DurableSwarmConfig;
import com.rorm.ai.swarm.EventId;
import com.rorm.ai.swarm.SwarmEvent;
import com.rorm.ai.swarm.agents.DurableSwarmStep.StepOutput;
import com.rorm.ml.MlTrainingService;
import com.rorm.ml.PipelineSpecConverter;
import com.rorm.ml.dto.PipelineSpecRequest;
import com.rorm.ml.stream.JobEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

@Slf4j
@RequiredArgsConstructor
public class CompilePhase {

    private final DurableSwarmConfig config;
    private final PipelineSpecConverter pipelineSpecConverter;
    private final MlTrainingService mlService;

    public Output execute(EventId rebuttalId, String hypothesisId,
                          String specBlock, String domainRaw) {
        var ctx = SwarmScope.ctx();
        var anchorTag = SwarmScope.anchorTag();

        var compilerId = EventId.child("compiler",
            ctx.journal().randomUUID(),
            List.of(rebuttalId),
            Map.of("anchor", anchorTag, "hypothesis", hypothesisId));

        var compiler = ctx.step(config.executorCompiler(), PipelineSpecRequest.class,
            SwarmEvent.CompilerStarted::new, SwarmEvent.CompilerFinished::new);

        log.info("[swarm] Compiling hypothesis {}", hypothesisId);
        var prompt = config.executorCompiler().userPromptTemplate()
            .replace("{{HYPOTHESIS_SPEC}}", specBlock)
            .replace("{{DOMAIN_KNOWLEDGE}}", domainRaw);
        var compilerResult = compiler.execute(compilerId, prompt, ctx.events());

        log.info("[swarm] Submitting pipeline for {}", hypothesisId);
        var pipelineResult = submitPipeline(compilerResult.dto(), hypothesisId);

        return new Output(compilerResult, pipelineResult);
    }

    private JobEvent submitPipeline(PipelineSpecRequest spec, String hypothesisId) {
        var input = SwarmScope.ctx().input();
        var request = pipelineSpecConverter.convert(
            spec, hypothesisId + " causal verification", input.modelSpace(), input.schema());
        return mlService.submit(request).await();
    }

    public record Output(StepOutput<PipelineSpecRequest> compiler, JobEvent pipelineResult) {}
}
