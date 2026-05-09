package com.rorm.ai.swarm.knowledge;

import java.util.Set;

/**
 * Search request against the swarm knowledge store. The {@code scopes} set
 * widens the search across {@link KnowledgeScope#RUN} (current run's hot
 * memory) and {@link KnowledgeScope#DATASET} (cross-run dataset memory) as
 * a single union. {@code runId} and {@code schema} are required regardless
 * of scope so the implementation can apply the correct metadata filter.
 *
 * @param runId  current run identifier, used to filter
 *               {@link KnowledgeScope#RUN} matches
 * @param schema dataset schema, used to filter
 *               {@link KnowledgeScope#DATASET} matches
 * @param query  natural-language search query
 * @param scopes union of scopes to search; non-empty
 * @param topK   maximum number of matches to return; must be {@code > 0}
 */
public record KnowledgeSearchRequest(
    String runId,
    String schema,
    String query,
    Set<KnowledgeScope> scopes,
    int topK
) {

    public KnowledgeSearchRequest {
        if (scopes.isEmpty()) {
            throw new IllegalArgumentException(
                "KnowledgeSearchRequest requires at least one scope");
        }
        if (topK <= 0) {
            throw new IllegalArgumentException(
                "KnowledgeSearchRequest.topK must be positive, got " + topK);
        }
        scopes = Set.copyOf(scopes);
    }
}
