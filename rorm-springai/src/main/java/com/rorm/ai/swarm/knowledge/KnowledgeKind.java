package com.rorm.ai.swarm.knowledge;

/**
 * Taxonomy for entries written into the {@link SwarmKnowledgeStore}. Lets
 * tools and prompts filter by intent without re-classifying free-form
 * content.
 */
public enum KnowledgeKind {

    /**
     * External literature, web search result, or domain background — the
     * shape of "what is generally known about X."
     */
    RESEARCH,

    /**
     * Empirical observation derived from the dataset itself — distributions,
     * relationships, oddities discovered during exploration.
     */
    OBSERVATION,

    /**
     * A causal or correlational finding the swarm has settled on, often a
     * pipeline result or a verified hypothesis.
     */
    FINDING,

    /**
     * Evidence that contradicts or weakens a candidate hypothesis. Stored
     * deliberately so future runs can pre-empt the same dead end.
     */
    COUNTER_EVIDENCE,

    /**
     * Methodological note: a useful tactic, a gotcha, a parameterisation
     * that worked. Persists across runs at dataset scope.
     */
    METHOD_NOTE
}
