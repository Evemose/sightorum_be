package com.rorm.ai.swarm.communication;

import com.rorm.DurableRuntime;
import com.rorm.JobSpec;
import com.rorm.ai.anthropic.ServerToolGeneration;
import com.rorm.ai.anthropic.StreamToolCallGeneration;
import com.rorm.ai.anthropic.ThinkingGeneration;
import com.rorm.ai.anthropic.ToolRoundGeneration;
import com.rorm.ai.swarm.ContentHash;
import com.rorm.ai.swarm.EventId;
import com.rorm.ai.swarm.executor.ProgressInput;
import com.rorm.ai.swarm.executor.ProgressRound;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.*;
import org.springframework.ai.chat.model.Generation;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Per-call streaming advisor that turns each assistant round into one
 * fire-and-forget Restate sub-invocation of {@link com.rorm.ai.swarm.executor.ProgressExecutor}.
 * The advisor itself does no I/O — it only accumulates the round's
 * thinking and text, then dispatches at round close.
 * <p>
 * A round is the slice between tool-call boundaries (closed by a
 * {@link StreamToolCallGeneration} on the wire) plus the trailing slice
 * between the last tool call and stream completion (the final answer
 * round, flushed in {@code doFinally}). Each non-empty round is
 * dispatched as a sub-invocation keyed by a stable content hash —
 * {@code ContentHash(parent eventId.token, thinking, text)} — so a
 * parent-invocation replay re-issues the same dispatch with the same
 * session id and Restate's ingress deduplicates. The child invocation
 * paraphrases via {@code AiChatService} and publishes the resulting
 * {@link com.rorm.ai.swarm.SwarmStreamEvent.AgentProgress} inside its
 * own journal — no shared journal between parent and child, no
 * cross-thread journal access from this advisor.
 */
@Slf4j
public class ProgressReportAdvisor implements CallAdvisor, StreamAdvisor {

    private final DurableRuntime runtime;
    private final String runId;
    private final EventId eventId;
    private final String schema;
    private final String character;
    private final RoundBuilder builder;
    private final List<ProgressRound> priorRounds = new ArrayList<>();

    public ProgressReportAdvisor(DurableRuntime runtime, String runId, EventId eventId,
                                 String schema, String character) {
        this.runtime = runtime;
        this.runId = runId;
        this.eventId = eventId;
        this.schema = schema;
        this.character = character == null ? "" : character;
        this.builder = new RoundBuilder(this::dispatchRound);
    }

    private void dispatchRound(RoundCapture round) {
        var roundIndex = priorRounds.size();
        var sessionId = stableSessionId(round, roundIndex);
        var snapshot = List.copyOf(priorRounds);
        var input = new ProgressInput(runId, eventId, schema, character,
            snapshot, round.thinking(), round.text());
        try {
            runtime.submitAsync(sessionId, new JobSpec(
                "progressExecutor", "process",
                new Object[]{input},
                new String[]{ProgressInput.class.getName()}));
        } catch (Exception e) {
            log.warn("[progress-advisor] dispatch failed for {} ({}): {}",
                eventId.kind(), eventId.shortToken(), e.toString());
        }
        priorRounds.add(new ProgressRound(round.thinking(), round.text()));
    }

    private String stableSessionId(RoundCapture round, int roundIndex) {
        var hash = ContentHash.of(Map.of(
            "kind", "progress",
            "parent", eventId.token().toString(),
            "round", String.valueOf(roundIndex),
            "thinking", round.thinking(),
            "text", round.text()));
        return "progress-" + hash;
    }

    @Override
    public String getName() {
        return "ProgressReportAdvisor";
    }

    @Override
    public int getOrder() {
        return Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER + 200;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        return chain.nextCall(request);
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return chain.nextStream(request)
            .doOnNext(this::processChunk)
            .doFinally(_ -> builder.flushFinal());
    }

    private void processChunk(ChatClientResponse response) {
        var chat = response.chatResponse();
        if (chat == null) {
            return;
        }
        for (var gen : chat.getResults()) {
            apply(gen);
        }
    }

    private void apply(Generation gen) {
        if (gen instanceof StreamToolCallGeneration) {
            builder.closeRoundOnToolCall();
            return;
        }
        if (gen instanceof ThinkingGeneration thinking) {
            builder.appendThinking(thinking.getThinkingText());
            return;
        }
        if (gen instanceof ServerToolGeneration || gen instanceof ToolRoundGeneration) {
            return;
        }
        var msg = gen.getOutput();
        var text = msg.getText();
        if (text != null && !text.isEmpty()) {
            builder.appendText(text);
        }
    }

    private static final class RoundBuilder {

        private final java.util.function.Consumer<RoundCapture> onRound;
        private final StringBuilder text = new StringBuilder();
        private final StringBuilder thinking = new StringBuilder();

        RoundBuilder(java.util.function.Consumer<RoundCapture> onRound) {
            this.onRound = onRound;
        }

        synchronized void appendText(String t) {
            if (t == null || t.isEmpty()) {
                return;
            }
            text.append(t);
        }

        synchronized void appendThinking(String t) {
            if (t == null || t.isEmpty()) {
                return;
            }
            thinking.append(t);
        }

        synchronized void closeRoundOnToolCall() {
            emitIfNonEmpty();
        }

        private void emitIfNonEmpty() {
            if (thinking.isEmpty() && text.isEmpty()) {
                return;
            }
            var capture = new RoundCapture(thinking.toString(), text.toString());
            thinking.setLength(0);
            text.setLength(0);
            onRound.accept(capture);
        }

        synchronized void flushFinal() {
            emitIfNonEmpty();
        }
    }

    private record RoundCapture(String thinking, String text) {}
}
