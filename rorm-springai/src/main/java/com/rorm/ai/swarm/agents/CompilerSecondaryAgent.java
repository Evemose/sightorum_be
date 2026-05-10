package com.rorm.ai.swarm.agents;

import com.rorm.ai.chat.AiChatService;
import com.rorm.ai.prompt.PromptPlaceholders;
import com.rorm.ai.swarm.DurableSwarmConfig;
import com.rorm.ai.swarm.dto.CompilerResultDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Compiler-specific {@link SecondarySwarmAgent}.
 * <p>
 * The compiler is contractually required to call {@code executePipeline}
 * at least once on the finalized PipelineSpec — that tool deposits each
 * (spec, JobEvent) pair into the {@link
 * com.rorm.ai.swarm.communication.LastExecutionRoundHolder} the executor
 * installed in the tool context. This agent enforces the contract
 * (rerouting if the holder is empty), then delegates to a
 * {@link SummarizingSecondaryAgent} which uses the configured summarizer
 * to extract the typed {@link CompilerResultDTO} from the compiler's
 * raw text. The DTO carries reasoning over the runs (verdicts, selected
 * ids, narrative) — never the spec verbatim, which lives in the
 * side-channel.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CompilerSecondaryAgent implements SecondarySwarmAgent<CompilerResultDTO> {

    private static final int MAX_REROUTE_ATTEMPTS = 3;

    private static final String REROUTE_PROMPT = """
        Your previous response did not call executePipeline. Calling it on the
        finalized PipelineSpec is mandatory — the engine captures the spec ONLY
        through that tool call, and your textual output is not parsed otherwise.

        Take the PipelineSpec described in your previous response, build the
        corresponding PipelineSpecRequest payload, and call executePipeline
        with it. If the run reports failures or weak metrics, revise the spec
        and call executePipeline again until you are satisfied with the
        result.
        
        If you change ANY decisions from your previous response while building
        the executed spec, briefly summarize at the top of your reply what
        changed and why before invoking the tool. If nothing changed, say so
        explicitly. End your reply only after at least one successful
        executePipeline call has returned.""";

    private final DurableSwarmConfig config;
    private final AiChatService chatService;
    private final PromptPlaceholders promptPlaceholders;

    @Override
    public SecondaryAgentResult<CompilerResultDTO> produce(SecondaryAgentContext context) {
        if (!thisStepProducedAnyRun(context)) {
            return rerouteUntilExecuted(context);
        }
        return summarize(context, context.raw());
    }

    private static boolean thisStepProducedAnyRun(SecondaryAgentContext context) {
        var myToken = context.input().eventId().token();
        return context.toolCallRegistry()
            .snapshot(context.input().runId()).stream()
            .anyMatch(r -> r.producerEventId().token().equals(myToken));
    }

    private SecondaryAgentResult<CompilerResultDTO> rerouteUntilExecuted(SecondaryAgentContext context) {
        var combined = new StringBuilder(context.raw());
        for (var attempt = 1; attempt <= MAX_REROUTE_ATTEMPTS; attempt++) {
            log.warn("[swarm] Compiler chatId={} did not call executePipeline — reroute attempt {}/{}",
                context.chatId(), attempt, MAX_REROUTE_ATTEMPTS);
            var retryRaw = context.streamPrimitive().stream(REROUTE_PROMPT);
            combined.append("\n\n--- compiler reroute ").append(attempt)
                .append(" (executePipeline was missing) ---\n\n").append(retryRaw);
            if (thisStepProducedAnyRun(context)) {
                return summarize(context, combined.toString());
            }
        }
        throw new IllegalStateException(
            "Compiler chatId=" + context.chatId() + " did not call executePipeline after "
            + MAX_REROUTE_ATTEMPTS + " reroutes; refusing to produce a compiler result without an executed run.");
    }

    private SecondaryAgentResult<CompilerResultDTO> summarize(SecondaryAgentContext context, String raw) {
        var summarizer = new SummarizingSecondaryAgent<>(
            config.summarizer(), chatService, promptPlaceholders,
            context.modelSpace(), CompilerResultDTO.class);
        var withRaw = new SecondaryAgentContext(
            context.input(), context.chatId(), raw, context.modelSpace(),
            context.firstLevelConfig(), context.streamPrimitive(),
            context.pipelineSpecHolder(), context.toolCallRegistry());
        return summarizer.produce(withRaw);
    }
}
