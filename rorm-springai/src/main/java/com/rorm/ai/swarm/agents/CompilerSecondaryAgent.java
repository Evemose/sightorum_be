package com.rorm.ai.swarm.agents;

import com.rorm.ai.swarm.communication.PipelineSpecHolder;
import com.rorm.ml.dto.PipelineSpecRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Compiler-specific {@link SecondarySwarmAgent} that bypasses LLM
 * summarization. The compiler is contractually required to finish its
 * step by calling {@code validatePipelineSpec}; on success the tool
 * deposits the typed {@link PipelineSpecRequest} into the
 * {@link PipelineSpecHolder} the executor installed in the tool
 * context. This agent reads the holder, and only when the compiler
 * skipped the call does it route back into the same conversation with
 * a follow-up user prompt asking it to pipe its output through the tool
 * — preserving its prior turn so it can describe what changed.
 */
@Slf4j
@Component
public class CompilerSecondaryAgent implements SecondarySwarmAgent<PipelineSpecRequest> {

    private static final int MAX_REROUTE_ATTEMPTS = 3;

    private static final String REROUTE_PROMPT = """
        Your previous response did not call validatePipelineSpec. Calling it on the
        finalized PipelineSpec is mandatory — the engine captures the spec ONLY
        through that tool call, and your textual output is not parsed otherwise.
        
        Take the PipelineSpec described in your previous response, build the
        corresponding PipelineSpecRequest payload, and call validatePipelineSpec
        with it. If validation reports errors, fix the spec and call again until
        it passes.
        
        If you change ANY decisions from your previous response while building
        the validated spec, briefly summarize at the top of your reply what
        changed and why before invoking the tool. If nothing changed, say so
        explicitly. End your reply only after validatePipelineSpec has returned
        a successful validation.""";

    @Override
    public SecondaryAgentResult<PipelineSpecRequest> produce(SecondaryAgentContext context) {
        var holder = context.pipelineSpecHolder();
        var existing = holder.get();
        return existing
            .map(pipelineSpecRequest -> new SecondaryAgentResult<>(pipelineSpecRequest, context.raw()))
            .orElseGet(() -> rerouteUntilFinalized(context, holder));
    }

    private SecondaryAgentResult<PipelineSpecRequest> rerouteUntilFinalized(
        SecondaryAgentContext context, PipelineSpecHolder holder
    ) {
        var combined = new StringBuilder(context.raw());
        for (var attempt = 1; attempt <= MAX_REROUTE_ATTEMPTS; attempt++) {
            log.warn("[swarm] Compiler chatId={} did not call validatePipelineSpec — reroute attempt {}/{}",
                context.chatId(), attempt, MAX_REROUTE_ATTEMPTS);
            var retryRaw = context.streamPrimitive().stream(REROUTE_PROMPT);
            combined.append("\n\n--- compiler reroute ").append(attempt)
                .append(" (validatePipelineSpec was missing) ---\n\n").append(retryRaw);
            var spec = holder.get();
            if (spec.isPresent()) {
                return new SecondaryAgentResult<>(spec.get(), combined.toString());
            }
        }
        throw new IllegalStateException(
            "Compiler chatId=" + context.chatId() + " did not call validatePipelineSpec after "
            + MAX_REROUTE_ATTEMPTS + " reroutes; refusing to fall back to summarizer-derived spec.");
    }
}
