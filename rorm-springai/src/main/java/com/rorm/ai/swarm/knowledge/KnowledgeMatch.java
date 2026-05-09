package com.rorm.ai.swarm.knowledge;

import java.util.Map;

/**
 * Single match returned by a knowledge-store search. Reports the original
 * content, the metadata attached when the entry was written, and the
 * similarity score the underlying vector store assigned.
 *
 * @param content    stored text
 * @param kind       original {@link KnowledgeKind} of the entry
 * @param scope      which scope the match came from
 * @param authorKind agent role that wrote the entry
 * @param score      implementation-defined similarity score; higher is
 *                   typically better but the absolute value is not
 *                   portable across vector store backends
 * @param tags       metadata attached at write time
 */
public record KnowledgeMatch(
    String content,
    KnowledgeKind kind,
    KnowledgeScope scope,
    String authorKind,
    double score,
    Map<String, String> tags
) {

    public KnowledgeMatch {
        tags = Map.copyOf(tags);
    }
}
