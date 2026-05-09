package com.rorm.ai.swarm.knowledge;

/**
 * Metadata field names written to every {@code Document} stored in the
 * underlying vector store. Centralising the keys keeps the writer and the
 * filter-expression reader in sync — a name drift between them would
 * silently exclude entries from search results.
 */
final class KnowledgeMetadataKeys {

    static final String SCOPE = "swarmScope";
    static final String RUN_ID = "swarmRunId";
    static final String SCHEMA = "swarmSchema";
    static final String AUTHOR_KIND = "swarmAuthorKind";
    static final String KIND = "swarmKind";

    private KnowledgeMetadataKeys() {
    }
}
