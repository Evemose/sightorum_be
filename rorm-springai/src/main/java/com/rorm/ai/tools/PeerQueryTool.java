package com.rorm.ai.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rorm.ai.JournaledTool;
import com.rorm.ai.swarm.communication.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Communication-bus tools given to every first-level agent: peer
 * questioning by role, upstream questioning along the swarm DAG, and
 * a swarm-state inspection used to decide who is far enough along to
 * be worth asking.
 */
@Slf4j
@Component
@JournaledTool
@RequiredArgsConstructor
public class PeerQueryTool {

    private final SwarmCommunicationBuffer buffer;
    private final SwarmContext swarmContext;
    private final ObjectMapper objectMapper;

    @Tool(
        name = "askPeer",
        description = """
            Ask another role a question. Every agent of that role
            (other than yourself) is polled on their own chat with the
            question text as a new user message; you receive one
            answer per peer. An agent already waiting on you is
            refused at the context boundary so mutual blocks are
            impossible — the refused peer's answer marks itself as
            "deadlock prevented" rather than blocking.
            
            This tool is useful for dataset-wide context questions, and will return scarce
            "i dont know" if you ask something related to your specific case only
            """
    )
    public String askPeer(
        @ToolParam(description = "Target role and question text") Question question,
        ToolContext toolContext
    ) {
        var ctx = SwarmToolContext.from(toolContext);
        var answers = buffer.ask(ctx.runId(), ctx.askerChatId(), question);
        log.debug("[peer-query] {} → role={}: {} answer(s)",
            ctx.askerRole(), question.role(), answers.size());
        return write(answers);
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize peer-query response", e);
        }
    }

    @Tool(
        name = "askUpstream",
        description = """
            Walk your upstream thread in the swarm DAG and ask the
            nearest ancestor of the given role. The buffer traverses
            your parent links breadth-first and stops at the first
            agent whose role matches; you receive that one answer.
            Returns an empty list when no upstream agent of that role
            exists.
            
            This tool is useful for specific context questions, when you need to inquire on upstream
            agents reasoning regarding their decisions and context
            """
    )
    public String askUpstream(
        @ToolParam(description = "Role of the upstream agent to consult, e.g. \"scout\", \"generator\"") String role,
        @ToolParam(description = "Question to put to that upstream agent") String question,
        ToolContext toolContext
    ) {
        var ctx = SwarmToolContext.from(toolContext);
        var answers = buffer.askUpstream(ctx.runId(), ctx.askerChatId(), role, question);
        log.debug("[peer-query] {} → upstream({}): {} answer(s)",
            ctx.askerRole(), role, answers.size());
        return write(answers);
    }

    @Tool(
        name = "inspectSwarm",
        description = """
            Returns your position in the swarm and who is around you:
            
            - yourRole: the role you are currently playing.
            - yourUpstreamChain: the distinct roles whose output fed
              into your task, in breadth-first order from your nearest
              parent outward. This is the chain you can call
              askUpstream against.
            - askable: every other registered agent in this run with
              their role and current round number (0 = produced
              nothing yet; agents on round 0 or 1 usually have nothing
              useful to say yet, so prefer those that have advanced).
            
            Results change between rounds; if nobody useful is around
            yet, call this again a few rounds later.
            """
    )
    public String inspectSwarm(ToolContext toolContext) {
        var ctx = SwarmToolContext.from(toolContext);
        var agents = swarmContext.list(ctx.runId());
        var asker = findByChatId(agents, ctx.askerChatId());
        var view = new SwarmInspection(
            ctx.askerRole(),
            asker == null ? List.of() : upstreamRoles(asker, agents),
            otherAgents(agents, ctx.askerChatId()));
        return write(view);
    }

    private static AgentState findByChatId(List<AgentState> agents, String chatId) {
        return agents.stream()
            .filter(a -> a.chatId().equals(chatId))
            .findFirst()
            .orElse(null);
    }

    private static List<String> upstreamRoles(AgentState asker, List<AgentState> agents) {
        var byToken = indexByToken(agents);
        var seenRoles = new HashSet<String>();
        var seenTokens = new HashSet<UUID>();
        var chain = new ArrayList<String>();
        var queue = new ArrayDeque<>(asker.parentTokens());
        while (!queue.isEmpty()) {
            var token = queue.poll();
            if (!seenTokens.add(token)) {
                continue;
            }
            var ancestor = byToken.get(token);
            if (ancestor == null) {
                continue;
            }
            if (seenRoles.add(ancestor.role())) {
                chain.add(ancestor.role());
            }
            queue.addAll(ancestor.parentTokens());
        }
        return chain;
    }

    private static List<AgentBrief> otherAgents(List<AgentState> agents, String askerChatId) {
        return agents.stream()
            .filter(a -> !a.chatId().equals(askerChatId))
            .map(PeerQueryTool::toBrief)
            .toList();
    }

    private static Map<UUID, AgentState> indexByToken(List<AgentState> agents) {
        var map = new HashMap<UUID, AgentState>();
        for (var a : agents) {
            map.put(a.agentToken(), a);
        }
        return map;
    }

    private static AgentBrief toBrief(AgentState state) {
        return new AgentBrief(state.role(), state.currentSession().rounds().size());
    }

    private record AgentBrief(String role, int round) {
    }

    private record SwarmInspection(
        String yourRole,
        List<String> yourUpstreamChain,
        List<AgentBrief> askable
    ) {
    }
}
