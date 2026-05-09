package com.rorm.ai.swarm.knowledge;

import com.rorm.ai.swarm.ContentHash;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

import java.util.*;

/**
 * {@link SwarmKnowledgeStore} backed by a Spring AI {@link VectorStore}.
 * Both run-scoped and dataset-scoped entries live in the same index; the
 * scope is recorded as a metadata field on each document and applied as a
 * filter at search and delete time.
 * <p>
 * Document IDs are derived from a content hash that includes the scope key
 * (run id or schema) so the same content stored at both scopes produces
 * two distinct documents and writing the same content twice is idempotent.
 */
@Slf4j
@RequiredArgsConstructor
public class VectorStoreSwarmKnowledgeStore implements SwarmKnowledgeStore {

    private final VectorStore vectorStore;

    @Override
    public KnowledgeRef store(KnowledgeEntry entry) {
        var ids = new ArrayList<String>();
        var stored = EnumSet.noneOf(KnowledgeScope.class);
        for (var scope : entry.scopes()) {
            var doc = toDocument(entry, scope);
            vectorStore.add(List.of(doc));
            ids.add(doc.getId());
            stored.add(scope);
        }
        return new KnowledgeRef(ids.getFirst(), stored);
    }

    private Document toDocument(KnowledgeEntry entry, KnowledgeScope scope) {
        var metadata = new HashMap<String, Object>(entry.tags());
        metadata.put(KnowledgeMetadataKeys.SCOPE, scope.name());
        metadata.put(KnowledgeMetadataKeys.SCHEMA, entry.schema());
        metadata.put(KnowledgeMetadataKeys.AUTHOR_KIND, entry.authorKind());
        metadata.put(KnowledgeMetadataKeys.KIND, entry.kind().name());
        if (scope == KnowledgeScope.RUN) {
            metadata.put(KnowledgeMetadataKeys.RUN_ID, entry.runId());
        }
        return Document.builder()
            .id(idFor(entry, scope))
            .text(entry.content())
            .metadata(metadata)
            .build();
    }

    private static String idFor(KnowledgeEntry entry, KnowledgeScope scope) {
        var attrs = new TreeMap<String, String>();
        attrs.put("content", entry.content());
        attrs.put("scope", scope.name());
        attrs.put("scopeKey", scope == KnowledgeScope.RUN ? entry.runId() : entry.schema());
        attrs.put("authorKind", entry.authorKind());
        attrs.put("kind", entry.kind().name());
        return ContentHash.of(attrs).toString();
    }

    @Override
    public List<KnowledgeMatch> search(KnowledgeSearchRequest request) {
        var allMatches = new ArrayList<KnowledgeMatch>();
        for (var scope : request.scopes()) {
            allMatches.addAll(searchScope(request, scope));
        }
        return allMatches.stream()
            .sorted((a, b) -> Double.compare(b.score(), a.score()))
            .limit(request.topK())
            .toList();
    }

    private List<KnowledgeMatch> searchScope(KnowledgeSearchRequest request, KnowledgeScope scope) {
        var filter = scopeFilter(request, scope);
        var docs = vectorStore.similaritySearch(SearchRequest.builder()
            .query(request.query())
            .topK(request.topK())
            .filterExpression(filter)
            .build());
        return docs == null ? List.of() : docs.stream().map(d -> toMatch(d, scope)).toList();
    }

    private static Filter.Expression scopeFilter(KnowledgeSearchRequest request, KnowledgeScope scope) {
        var b = new FilterExpressionBuilder();
        var scopeOnly = b.eq(KnowledgeMetadataKeys.SCOPE, scope.name());
        var key = scope == KnowledgeScope.RUN
            ? b.eq(KnowledgeMetadataKeys.RUN_ID, request.runId())
            : b.eq(KnowledgeMetadataKeys.SCHEMA, request.schema());
        return b.and(scopeOnly, key).build();
    }

    private static KnowledgeMatch toMatch(Document doc, KnowledgeScope scope) {
        var meta = doc.getMetadata();
        return new KnowledgeMatch(
            Optional.ofNullable(doc.getText()).orElse(""),
            KnowledgeKind.valueOf((String) meta.get(KnowledgeMetadataKeys.KIND)),
            scope,
            (String) meta.get(KnowledgeMetadataKeys.AUTHOR_KIND),
            scoreOf(doc),
            tagsOf(meta));
    }

    private static double scoreOf(Document doc) {
        var s = doc.getScore();
        return s == null ? 0.0 : s;
    }

    private static Map<String, String> tagsOf(Map<String, Object> meta) {
        var out = new HashMap<String, String>();
        for (var entry : meta.entrySet()) {
            if (entry.getKey().startsWith("swarm")) {
                continue;
            }
            out.put(entry.getKey(), String.valueOf(entry.getValue()));
        }
        return out;
    }

    @Override
    public void completeRun(String runId) {
        try {
            vectorStore.delete(runScopeFilter(runId));
        } catch (UnsupportedOperationException e) {
            log.debug("[swarm-knowledge] backing store does not support filter-delete; "
                      + "run-scoped entries will rely on the store's own retention policy", e);
        }
    }

    private static Filter.Expression runScopeFilter(String runId) {
        var b = new FilterExpressionBuilder();
        var scopeOnly = b.eq(KnowledgeMetadataKeys.SCOPE, KnowledgeScope.RUN.name());
        var run = b.eq(KnowledgeMetadataKeys.RUN_ID, runId);
        return b.and(scopeOnly, run).build();
    }
}
