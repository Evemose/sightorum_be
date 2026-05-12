package com.rorm.ai.swarm.communication;

import com.rorm.ai.ModelSpaceResolver;
import com.rorm.ai.anthropic.AnthropicChatOptions.CacheTTL;
import com.rorm.ai.chat.AiChatService;
import com.rorm.ai.chat.ChatRequest;
import com.rorm.ai.chat.ThinkingLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Gatherers;

/**
 * Default {@link SwarmCommunicationBuffer}. For each ask, finds every
 * registered agent of the question's target role (excluding the asker),
 * issues a chat call against their own {@code chatId} with the question
 * as a new user message, and returns the responses.
 * <p>
 * {@link AiChatService} is resolved lazily via {@link ObjectProvider} to
 * avoid a Spring DI cycle (chat service → tool group resolver → askPeer
 * tool → buffer).
 */
@Component
@RequiredArgsConstructor
public class SwarmCommunicationBufferImpl implements SwarmCommunicationBuffer {

    private final SwarmContext swarmContext;
    private final ObjectProvider<AiChatService> chatServiceProvider;
    private final ModelSpaceResolver modelSpaceResolver;

    @Override
    public List<Answer> ask(String runId, String askerChatId, Question question) {
        return swarmContext.list(runId).stream()
            .filter(e -> !e.chatId().equals(askerChatId))
            .filter(e -> e.role().equals(question.role()))
            .gather(Gatherers.mapConcurrent(10, e -> poll(e, question.text())))
            .toList();
    }

    private Answer poll(AgentState target, String questionText) {
        var modelSpace = modelSpaceResolver.resolve(target.schema());
        var request = ChatRequest.usingData(target.schema(), modelSpace)
            .withSystemPrompt(target.agentConfig().systemPrompt())
            .withModelName(target.agentConfig().model())
            .withChatId(target.chatId())
            .withThinkingLevel(ThinkingLevel.MEDIUM)
            .withCachingStrategyFunction(_ -> CacheTTL.NONE)
            .ask(questionText + "\nIf you don't know the answer, say 'I don't know'.");
        var responseText = chatServiceProvider.getObject().call(request);
        return new Answer(target.role(), responseText);
    }

    @Override
    public List<Answer> askUpstream(String runId, String askerChatId, String role, String questionText) {
        var agents = swarmContext.list(runId);
        var asker = findByChatId(agents, askerChatId);
        if (asker == null) {
            return List.of();
        }
        var byToken = indexByToken(agents);
        var match = bfsForRole(asker, role, byToken);
        if (match == null) {
            return List.of();
        }
        return List.of(poll(match, questionText));
    }

    private static AgentState findByChatId(List<AgentState> agents, String chatId) {
        return agents.stream()
            .filter(e -> e.chatId().equals(chatId))
            .findFirst()
            .orElse(null);
    }

    private static Map<UUID, AgentState> indexByToken(List<AgentState> agents) {
        var map = new HashMap<UUID, AgentState>();
        for (var a : agents) {
            map.put(a.agentToken(), a);
        }
        return map;
    }

    private static AgentState bfsForRole(AgentState asker, String role,
                                         Map<UUID, AgentState> byToken) {
        var visited = new HashSet<UUID>();
        var queue = new ArrayDeque<>(asker.parentTokens());
        while (!queue.isEmpty()) {
            var token = queue.poll();
            if (!visited.add(token)) {
                continue;
            }
            var ancestor = byToken.get(token);
            if (ancestor == null) {
                continue;
            }
            if (ancestor.role().equals(role)) {
                return ancestor;
            }
            queue.addAll(ancestor.parentTokens());
        }
        return null;
    }
}
