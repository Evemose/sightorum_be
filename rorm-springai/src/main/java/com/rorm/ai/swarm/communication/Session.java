package com.rorm.ai.swarm.communication;

import java.util.List;

/**
 * One agent's working session — the whole arc from receiving the task
 * to (eventually) delivering the final answer. A session is composed
 * of multiple {@link Round}s: every assistant turn between tool-call
 * boundaries is its own round, including the final round whose tool
 * list is empty. The current session for an agent is whatever rounds
 * the stream has produced so far; the last entry may be in-flight and
 * partial.
 */
public record Session(
    List<Round> rounds
) {

    public Session {
        rounds = List.copyOf(rounds);
    }

    public static Session empty() {
        return new Session(List.of());
    }
}
