package com.rorm.ai.swarm.communication;

import com.rorm.ai.anthropic.StreamToolCallGeneration;
import com.rorm.ai.anthropic.ThinkingGeneration;
import com.rorm.ai.anthropic.ToolRoundGeneration;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.*;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-call advisor attached to every first-level streaming agent
 * request. Builds typed {@link Round}s from the stream and appends
 * them to the agent's {@link AgentState} in {@link SwarmContext}.
 * <p>
 * Anthropic surfaces tool input and tool output only at stream
 * completion, in a single {@link ChatResponse} that aggregates every
 * tool round's {@link ToolRoundGeneration}. The advisor consumes that
 * end-of-stream chunk to build past tool rounds. The final round
 * (which has no {@code ToolRoundGeneration} because it has no tool
 * calls) gets its text from streamed deltas accumulated since the
 * last {@link StreamToolCallGeneration}.
 */
@RequiredArgsConstructor
public class SwarmContextStreamAdvisor implements CallAdvisor, StreamAdvisor {

    private final SwarmContext swarmContext;
    private final String runId;
    private final String chatId;

    @Override
    public String getName() {
        return "SwarmContextStreamAdvisor";
    }

    @Override
    public int getOrder() {
        return Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER + 100;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        return chain.nextCall(request);
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        var captured = new StreamCapture();
        return chain.nextStream(request)
            .doOnNext(resp -> captureChunk(resp, captured))
            .doOnComplete(() -> appendRoundsToContext(captured));
    }

    private void captureChunk(ChatClientResponse response, StreamCapture captured) {
        var chat = response.chatResponse();
        if (chat == null) {
            return;
        }
        if (chat.getResults().size() > 1) {
            captured.endOfStreamChunk = chat;
            return;
        }
        for (var gen : chat.getResults()) {
            captureLiveDelta(gen, captured);
        }
    }

    private void appendRoundsToContext(StreamCapture captured) {
        var rounds = captured.endOfStreamChunk == null
            ? List.of(new Round("", captured.finalRoundText.toString(), List.of()))
            : buildRounds(captured.endOfStreamChunk, captured.finalRoundText.toString());
        for (var round : rounds) {
            swarmContext.appendRound(runId, chatId, round);
        }
    }

    private static void captureLiveDelta(Generation gen, StreamCapture captured) {
        if (gen instanceof StreamToolCallGeneration) {
            captured.finalRoundText.setLength(0);
        } else if (gen.getClass() == Generation.class) {
            var text = gen.getOutput().getText();
            if (text != null && !text.isEmpty()) {
                captured.finalRoundText.append(text);
            }
        }
    }

    private static List<Round> buildRounds(ChatResponse chunk, String finalRoundText) {
        var rounds = new ArrayList<Round>();
        var pendingThinking = new StringBuilder();
        for (var gen : chunk.getResults()) {
            if (gen instanceof ThinkingGeneration thinking) {
                appendThinking(pendingThinking, thinking.getThinkingText());
            } else if (gen instanceof ToolRoundGeneration toolRound) {
                rounds.add(buildToolRound(pendingThinking.toString(), toolRound));
                pendingThinking.setLength(0);
            }
        }
        rounds.add(new Round(pendingThinking.toString(), finalRoundText, List.of()));
        return rounds;
    }

    private static void appendThinking(StringBuilder buffer, String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        if (buffer.length() > 0) {
            buffer.append("\n");
        }
        buffer.append(text);
    }

    private static Round buildToolRound(String thinking, ToolRoundGeneration gen) {
        var assistant = gen.getOutput();
        var responseById = indexResponses(gen.getToolResponse());
        var invocations = assistant.getToolCalls().stream()
            .map(call -> new ToolInvocation(
                call.name(), call.arguments(),
                responseById.getOrDefault(call.id(), "")))
            .toList();
        var text = assistant.getText() == null ? "" : assistant.getText();
        return new Round(thinking, text, invocations);
    }

    private static Map<String, String> indexResponses(ToolResponseMessage toolResponse) {
        var map = new HashMap<String, String>();
        for (var response : toolResponse.getResponses()) {
            map.put(response.id(), response.responseData());
        }
        return map;
    }

    private static final class StreamCapture {

        private final StringBuilder finalRoundText = new StringBuilder();
        private ChatResponse endOfStreamChunk;
    }
}
