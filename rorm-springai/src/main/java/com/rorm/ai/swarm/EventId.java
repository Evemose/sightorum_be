package com.rorm.ai.swarm;

import java.util.*;

/**
 * Identity of a single swarm event instance. Carries explicit parent links
 * so observers can reconstruct the swarm DAG reactively.
 * <p>
 * A parent reference to a non-observed event may be possible: downstream consumers
 * (e.g. the formatter) should treat unseen parents as ghost references and
 * never fabricate nodes to fill them in.
 *
 * @param kind    Agent kind, e.g. {@code "scout"}, {@code "generator"},
 *                {@code "compiler"}. Free-form string; not a closed enum.
 * @param token   Unique identifier for this event instance. Should come from
 *                the journaled random UUID source (e.g.
 *                {@code ctx.journal().randomUUID()}) so that deterministic
 *                replay produces identical ids.
 * @param parents Direct parent events in the DAG. Empty for roots. Plural
 *                because e.g. Generator consumes both Scout and Domain
 *                Researcher outputs.
 * @param tags    Free-form context (anchor, hypothesis, etc.) for display or
 *                correlation. Not used for graph topology.
 */
public record EventId(
    String kind,
    UUID token,
    List<EventId> parents,
    Map<String, String> tags
) {

    public EventId {
        parents = List.copyOf(parents);
        // Sort tags by key into a TreeMap to guarantee deterministic JSON
        // serialization order. {@code Map.of(...)} and {@code Map.copyOf(...)} both
        // return ImmutableCollections.MapN whose iteration order depends on a
        // hash perturbation seed re-randomized at every JVM startup — that
        // non-determinism propagates into Jackson-emitted JSON bytes for the
        // tags field and breaks Restate's deterministic-replay contract whenever
        // the recording and replay JVMs differ. TreeMap-by-key serialization
        // makes the on-the-wire form stable regardless of how the input was built.
        tags = Collections.unmodifiableSortedMap(new TreeMap<>(tags));
    }

    public static EventId root(String kind, UUID token) {
        return new EventId(kind, token, List.of(), Map.of());
    }

    public static EventId root(String kind, UUID token, Map<String, String> tags) {
        return new EventId(kind, token, List.of(), tags);
    }

    public static EventId child(String kind, UUID token, List<EventId> parents) {
        return new EventId(kind, token, parents, Map.of());
    }

    public static EventId child(String kind, UUID token, List<EventId> parents,
                                Map<String, String> tags) {
        return new EventId(kind, token, parents, tags);
    }

    /**
     * Short token prefix for display (first 4 hex chars).
     */
    public String shortToken() {
        return token.toString().substring(0, Math.min(4, token.toString().length()));
    }
}
