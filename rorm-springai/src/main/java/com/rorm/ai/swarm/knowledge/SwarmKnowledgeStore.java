package com.rorm.ai.swarm.knowledge;

import java.util.List;

/**
 * Semantic knowledge store shared by every agent in a swarm run. Provides
 * two scopes via {@link KnowledgeScope}: per-run hot memory cleared at
 * run end, and persistent per-dataset memory that survives across runs.
 * <p>
 * Both scopes back onto a single underlying Spring AI {@code VectorStore};
 * scope routing is implemented as a metadata filter on writes and reads.
 */
public interface SwarmKnowledgeStore {

    /**
     * Persists a knowledge entry into all scopes named in
     * {@link KnowledgeEntry#scopes()}. Returns a receipt naming the scopes
     * the entry was actually written to.
     */
    KnowledgeRef store(KnowledgeEntry entry);

    /**
     * Performs a semantic search across the requested scopes. Returns at
     * most {@link KnowledgeSearchRequest#topK} matches ordered by descending
     * similarity score. Never returns {@code null}; an empty list is the
     * "no match" signal.
     */
    List<KnowledgeMatch> search(KnowledgeSearchRequest request);

    /**
     * Releases per-run entries for {@code runId}. Persistent dataset-scope
     * entries are unaffected. Idempotent.
     */
    void completeRun(String runId);
}
