package com.rorm.ai.swarm.knowledge;

import java.util.Map;
import java.util.Set;

/**
 * Single entry to be written to the swarm knowledge store. Carries enough
 * provenance ({@code authorKind}, {@code runId}, {@code schema}, {@code
 * tags}) for future searches to attribute and filter results.
 *
 * @param runId      active swarm run identifier; required even for
 *                   {@link KnowledgeScope#DATASET}-only entries so that
 *                   cross-run audits can attribute the write
 * @param schema     data schema — drives {@link KnowledgeScope#DATASET}
 *                   routing and is recorded as metadata on every entry
 * @param authorKind role of the agent writing this entry
 * @param content    free-form text, ideally a single self-contained claim
 * @param kind       intent class — see {@link KnowledgeKind}
 * @param scopes     where this entry should be stored; non-empty
 * @param tags       additional metadata for downstream filtering
 */
public record KnowledgeEntry(
    String runId,
    String schema,
    String authorKind,
    String content,
    KnowledgeKind kind,
    Set<KnowledgeScope> scopes,
    Map<String, String> tags
) {

    public KnowledgeEntry {
        if (scopes.isEmpty()) {
            throw new IllegalArgumentException(
                "KnowledgeEntry requires at least one scope; "
                + "use KnowledgeScope.RUN, KnowledgeScope.DATASET, or both");
        }
        scopes = Set.copyOf(scopes);
        tags = Map.copyOf(tags);
    }
}
