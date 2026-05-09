package com.rorm.ai.swarm.knowledge;

import com.rorm.ai.swarm.ContentHash;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * In-process {@link SwarmKnowledgeStore} that keeps entries in concurrent
 * lists keyed by scope and uses literal token-overlap for retrieval.
 * Provided as the fallback when no vector store is on the application
 * context — typically the in-memory durable execution path used by tests
 * and local development.
 * <p>
 * Token overlap is a different semantic concern from cosine similarity
 * over embeddings: it captures literal lexical overlap rather than
 * meaning, so results can differ from the vector-backed implementation.
 * The interface contract holds — both return at most {@code topK} matches
 * ranked highest first — but rankings will not match.
 */
public class InMemorySwarmKnowledgeStore implements SwarmKnowledgeStore {

    private final ConcurrentMap<String, List<StoredEntry>> runEntries = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, List<StoredEntry>> datasetEntries = new ConcurrentHashMap<>();

    @Override
    public KnowledgeRef store(KnowledgeEntry entry) {
        var stored = EnumSet.noneOf(KnowledgeScope.class);
        var id = idFor(entry);
        for (var scope : entry.scopes()) {
            bucketForWrite(scope, entry).add(new StoredEntry(id, entry, scope));
            stored.add(scope);
        }
        return new KnowledgeRef(id, stored);
    }

    private static String idFor(KnowledgeEntry entry) {
        var attrs = new TreeMap<String, String>();
        attrs.put("content", entry.content());
        attrs.put("authorKind", entry.authorKind());
        attrs.put("kind", entry.kind().name());
        return ContentHash.of(attrs).toString();
    }

    private List<StoredEntry> bucketForWrite(KnowledgeScope scope, KnowledgeEntry entry) {
        var key = scope == KnowledgeScope.RUN ? entry.runId() : entry.schema();
        var bucket = scope == KnowledgeScope.RUN ? runEntries : datasetEntries;
        return bucket.computeIfAbsent(key, _ -> new CopyOnWriteArrayList<>());
    }

    @Override
    public List<KnowledgeMatch> search(KnowledgeSearchRequest request) {
        var queryTokens = tokenize(request.query());
        var pool = collectScopes(request);
        return pool.stream()
            .map(e -> e.score(queryTokens))
            .filter(m -> m.score() > 0)
            .sorted(Comparator.comparingDouble(KnowledgeMatch::score).reversed())
            .limit(request.topK())
            .toList();
    }

    private static Set<String> tokenize(String text) {
        return Arrays.stream(text.toLowerCase(Locale.ROOT).split("\\W+"))
            .filter(s -> !s.isBlank())
            .collect(Collectors.toUnmodifiableSet());
    }

    private List<StoredEntry> collectScopes(KnowledgeSearchRequest request) {
        var pool = new ArrayList<StoredEntry>();
        for (var scope : request.scopes()) {
            pool.addAll(bucketForRead(scope, request));
        }
        return pool;
    }

    private List<StoredEntry> bucketForRead(KnowledgeScope scope, KnowledgeSearchRequest request) {
        var key = scope == KnowledgeScope.RUN ? request.runId() : request.schema();
        var bucket = scope == KnowledgeScope.RUN ? runEntries : datasetEntries;
        return bucket.getOrDefault(key, List.of());
    }

    @Override
    public void completeRun(String runId) {
        runEntries.remove(runId);
    }

    private record StoredEntry(String id, KnowledgeEntry entry, KnowledgeScope scope) {

        KnowledgeMatch score(Set<String> queryTokens) {
            var contentTokens = tokenize(entry.content());
            var common = new HashSet<>(contentTokens);
            common.retainAll(queryTokens);
            var ratio = queryTokens.isEmpty() ? 0.0 : (double) common.size() / queryTokens.size();
            return new KnowledgeMatch(entry.content(), entry.kind(), scope,
                entry.authorKind(), ratio, Map.copyOf(entry.tags()));
        }
    }
}
