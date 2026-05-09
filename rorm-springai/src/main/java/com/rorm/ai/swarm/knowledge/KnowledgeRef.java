package com.rorm.ai.swarm.knowledge;

import java.util.Set;

/**
 * Receipt for a write to the swarm knowledge store. Reports the canonical
 * identifier of the stored entry and the set of scopes the underlying
 * vector store actually persisted it to (the intent set declared on
 * {@link KnowledgeEntry#scopes()} unless one or more scopes silently
 * dropped — implementations must report only what they wrote).
 */
public record KnowledgeRef(
    String id,
    Set<KnowledgeScope> storedIn
) {

    public KnowledgeRef {
        storedIn = Set.copyOf(storedIn);
    }
}
