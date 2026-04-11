package com.rorm.ai.swarm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rorm.StepJournal;
import com.rorm.ai.chat.AiChatService;
import com.rorm.ai.prompt.PromptPlaceholders;
import com.rorm.ai.swarm.SwarmResult.AnchorResult;
import com.rorm.ai.swarm.SwarmResult.HypothesisResult;
import com.rorm.ai.swarm.agents.SecondarySwarmAgent;
import com.rorm.ai.swarm.phase.*;
import com.rorm.ml.MlTrainingService;
import com.rorm.ml.PipelineSpecConverter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Sinks;

import java.util.function.Supplier;
import java.util.regex.Pattern;

@Slf4j
@Component
public class DurableSwarm {

    private static final Pattern ANCHOR_ENTITY = Pattern.compile("Anchor entity:\\s*(\\S+)");

    private final AiChatService chatService;
    private final DurableSwarmConfig config;
    private final PromptPlaceholders promptPlaceholders;
    private final ReconPhase reconPhase;
    private final GenPhase genPhase;
    private final CompilePhase compilePhase;
    private final NullPhase nullPhase;

    public DurableSwarm(AiChatService chatService, DurableSwarmConfig config,
                        PipelineSpecConverter pipelineSpecConverter, MlTrainingService mlService,
                        ObjectMapper objectMapper, PromptPlaceholders promptPlaceholders) {
        this.chatService = chatService;
        this.config = config;
        this.promptPlaceholders = promptPlaceholders;
        this.reconPhase = new ReconPhase(config);
        this.genPhase = new GenPhase(config);
        this.compilePhase = new CompilePhase(config, pipelineSpecConverter, mlService);
        this.nullPhase = new NullPhase(config, objectMapper);
    }

    public SwarmResult run(SwarmInput input) {
        return run(input, Sinks.many().replay().all());
    }

    public SwarmResult run(SwarmInput input, Sinks.Many<SwarmEvent> eventSink) {
        var ctx = createRunContext(input, eventSink);
        return ScopedValue.where(SwarmScope.CTX, ctx).call(() -> {
            var recon = reconPhase.execute();
            log.info("[swarm] Starting {} anchor pipelines", input.anchors().size());
            var anchorResults = ctx.journal().fanout("swarm:anchor", AnchorResult.class,
                input.anchors().stream()
                    .<Supplier<AnchorResult>>map(anchor -> () -> runAnchorPipeline(anchor, recon))
                    .toList());
            return new SwarmResult(recon.scout().dto(), recon.domain().dto(), anchorResults);
        });
    }

    private SwarmRunContext createRunContext(SwarmInput input, Sinks.Many<SwarmEvent> eventSink) {
        var summarizer = new SecondarySwarmAgent(
            config.summarizer(), chatService, input.schema(), input.modelSpace(), promptPlaceholders);
        return new SwarmRunContext(
            input, StepJournal.current(), eventSink, summarizer, chatService, promptPlaceholders);
    }

    private AnchorResult runAnchorPipeline(String anchor, ReconPhase.Output recon) {
        return ScopedValue.where(SwarmScope.ANCHOR_TAG, anchorTagOf(anchor)).call(() -> {
            var gen = genPhase.execute(anchor, recon.parentIds(),
                recon.scout().rawResponse(), recon.domain().rawResponse());
            var rebuttalId = gen.rebuttal().id();
            var rebuttalRaw = gen.rebuttal().rawResponse();
            var rebuttalDto = gen.rebuttal().dto();

            // Slice the globals ONCE for the entire rebuttal - same block is reused
            // for every hypothesis compile call below.
            var globalsBlock = rebuttalDto.renderGlobalsFromRaw(rebuttalRaw);
            var hypotheses = rebuttalDto.hypotheses();
            log.info("[swarm] {} hypotheses, running compiler + pipeline", hypotheses.size());
            var hypothesisResults = SwarmScope.ctx().journal().fanout(
                "swarm:hypothesis", HypothesisResult.class,
                hypotheses.stream()
                    .<Supplier<HypothesisResult>>map(h -> () -> runHypothesisPipeline(
                        rebuttalId, h.id(), combineSpec(h.sliceSpec(rebuttalRaw), globalsBlock),
                        recon.domain().rawResponse()))
                    .toList());

            return new AnchorResult(anchor, gen.chatId(), gen.generator().dto(),
                gen.sceptic().dto(), rebuttalDto, hypothesisResults);
        });
    }

    static String anchorTagOf(String anchor) {
        var firstLine = anchor.lines().findFirst().orElse("");
        var m = ANCHOR_ENTITY.matcher(firstLine);
        if (m.find()) {
            return m.group(1);
        }
        var trimmed = firstLine.trim();
        return trimmed.isEmpty() ? "anchor" : trimmed;
    }

    private HypothesisResult runHypothesisPipeline(EventId rebuttalId, String hypothesisId,
                                                   String specBlock, String domainRaw) {
        var compile = compilePhase.execute(rebuttalId, hypothesisId, specBlock, domainRaw);
        var compilerId = compile.compiler().id();
        var nullResult = nullPhase.execute(compilerId, hypothesisId, specBlock, domainRaw,
            compile.pipelineResult());
        return new HypothesisResult(hypothesisId, specBlock,
            compile.compiler().dto(), compile.pipelineResult(), nullResult.diagnosis().dto());
    }

    private static String combineSpec(String hypBlock, String globalsBlock) {
        return globalsBlock.isEmpty() ? hypBlock : hypBlock + "\n\n" + globalsBlock;
    }
}
