package com.rorm.ai.swarm.knowledge;

/**
 * Persistence scope for a knowledge entry. Each scope is a logically
 * separate index inside the underlying vector store; entries can be written
 * to one or both, and searches can target one or both.
 */
public enum KnowledgeScope {

    /**
     * Entry lives only for the duration of the current swarm run. Cleared
     * when the run completes. Used for hot working memory shared between
     * agents in the same run.
     */
    RUN,

    /**
     * Entry is persisted against the dataset (schema) and survives across
     * runs. Used for cumulative knowledge about a dataset — its quirks,
     * verified findings, methodological notes.
     */
    DATASET
}
