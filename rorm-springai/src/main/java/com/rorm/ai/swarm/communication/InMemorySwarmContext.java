package com.rorm.ai.swarm.communication;

import com.rorm.ai.swarm.AgentModelConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-process {@link SwarmContext}. Per-run map of agents, each holding
 * a synchronized append-only round list. Suitable for tests and the
 * in-memory durable runtime.
 */
public class InMemorySwarmContext implements SwarmContext {

    private final ConcurrentMap<String, RunState> runs = new ConcurrentHashMap<>();

    @Override
    public void register(AgentRegistration r) {
        runFor(r.runId()).register(r);
    }

    private RunState runFor(String runId) {
        return runs.computeIfAbsent(runId, _ -> new RunState());
    }

    @Override
    public void appendRound(String runId, String chatId, Round round) {
        var run = runs.get(runId);
        if (run == null) {
            return;
        }
        run.appendRound(chatId, round);
    }

    @Override
    public List<AgentState> list(String runId) {
        var run = runs.get(runId);
        return run == null ? List.of() : run.snapshot();
    }

    private static final class RunState {

        private final ConcurrentMap<String, MutableAgent> agents = new ConcurrentHashMap<>();
        private final List<String> order = new CopyOnWriteArrayList<>();

        void register(AgentRegistration r) {
            agents.compute(r.chatId(), (_, prior) -> {
                if (prior == null) {
                    order.add(r.chatId());
                    return new MutableAgent(r);
                }
                prior.updateMeta(r);
                return prior;
            });
        }

        void appendRound(String chatId, Round round) {
            var agent = agents.get(chatId);
            if (agent != null) {
                agent.appendRound(round);
            }
        }

        List<AgentState> snapshot() {
            return order.stream()
                .map(agents::get)
                .filter(java.util.Objects::nonNull)
                .map(MutableAgent::snapshot)
                .toList();
        }
    }

    private static final class MutableAgent {

        private final String chatId;
        private final UUID agentToken;
        private final List<UUID> parentTokens;
        private final String inputPrompt;
        private final List<Round> rounds = new ArrayList<>();
        private String role;
        private String schema;
        private AgentModelConfig agentConfig;

        MutableAgent(AgentRegistration r) {
            this.chatId = r.chatId();
            this.agentToken = r.agentToken();
            this.parentTokens = List.copyOf(r.parentTokens());
            this.inputPrompt = r.inputPrompt();
            this.role = r.role();
            this.schema = r.schema();
            this.agentConfig = r.agentConfig();
        }

        synchronized void updateMeta(AgentRegistration r) {
            this.role = r.role();
            this.schema = r.schema();
            this.agentConfig = r.agentConfig();
        }

        synchronized void appendRound(Round round) {
            rounds.add(round);
        }

        synchronized AgentState snapshot() {
            return new AgentState(chatId, agentToken, parentTokens, role, schema,
                agentConfig, inputPrompt, List.copyOf(rounds));
        }
    }
}
