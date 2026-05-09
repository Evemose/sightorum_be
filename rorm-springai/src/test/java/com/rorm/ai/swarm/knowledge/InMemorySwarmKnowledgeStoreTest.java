package com.rorm.ai.swarm.knowledge;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemorySwarmKnowledgeStoreTest {

    private static final String SCHEMA = "schemaA";
    private static final String AUTHOR = "scout";
    private static final Map<String, String> NO_TAGS = Map.of();

    private InMemorySwarmKnowledgeStore store;

    @BeforeEach
    void setUp() {
        store = new InMemorySwarmKnowledgeStore();
    }

    @Test
    void store_thenSearch_returnsMatchByKeywordOverlap() {
        var content = "vehicle refrigeration age increases excursion rate";
        store.store(new KnowledgeEntry(
            "runA", SCHEMA, AUTHOR, content, KnowledgeKind.FINDING,
            Set.of(KnowledgeScope.RUN), NO_TAGS));

        var results = store.search(new KnowledgeSearchRequest(
            "runA", SCHEMA, "vehicle excursion", Set.of(KnowledgeScope.RUN), 5));

        assertThat(results).hasSize(1);
        var match = results.getFirst();
        assertThat(match.content()).isEqualTo(content);
        assertThat(match.score()).isPositive();
        assertThat(match.scope()).isEqualTo(KnowledgeScope.RUN);
    }

    @Test
    void store_runScope_isolatedAcrossRuns() {
        store.store(new KnowledgeEntry(
            "runA", SCHEMA, AUTHOR, "vehicle excursion finding", KnowledgeKind.FINDING,
            Set.of(KnowledgeScope.RUN), NO_TAGS));

        var results = store.search(new KnowledgeSearchRequest(
            "runB", SCHEMA, "vehicle excursion", Set.of(KnowledgeScope.RUN), 5));

        assertThat(results).isEmpty();
    }

    @Test
    void store_datasetScope_sharedAcrossRuns() {
        store.store(new KnowledgeEntry(
            "runA", SCHEMA, AUTHOR, "vehicle excursion finding", KnowledgeKind.FINDING,
            Set.of(KnowledgeScope.DATASET), NO_TAGS));

        var results = store.search(new KnowledgeSearchRequest(
            "runB", SCHEMA, "vehicle excursion", Set.of(KnowledgeScope.DATASET), 5));

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().scope()).isEqualTo(KnowledgeScope.DATASET);
    }

    @Test
    void store_bothScopes_writesToBothBuckets() {
        var ref = store.store(new KnowledgeEntry(
            "runA", SCHEMA, AUTHOR, "vehicle excursion finding", KnowledgeKind.FINDING,
            EnumSet.of(KnowledgeScope.RUN, KnowledgeScope.DATASET), NO_TAGS));

        assertThat(ref.storedIn()).containsExactlyInAnyOrder(KnowledgeScope.RUN, KnowledgeScope.DATASET);

        var datasetMatches = store.search(new KnowledgeSearchRequest(
            "runB", SCHEMA, "vehicle excursion", Set.of(KnowledgeScope.DATASET), 5));
        assertThat(datasetMatches).hasSize(1);
        assertThat(datasetMatches.getFirst().scope()).isEqualTo(KnowledgeScope.DATASET);

        var runMatches = store.search(new KnowledgeSearchRequest(
            "runA", SCHEMA, "vehicle excursion", Set.of(KnowledgeScope.RUN), 5));
        assertThat(runMatches).hasSize(1);
        assertThat(runMatches.getFirst().scope()).isEqualTo(KnowledgeScope.RUN);
    }

    @Test
    void search_topK_capsResults() {
        for (int i = 0; i < 5; i++) {
            store.store(new KnowledgeEntry(
                "runA", SCHEMA, AUTHOR, "vehicle excursion entry " + i, KnowledgeKind.FINDING,
                Set.of(KnowledgeScope.RUN), NO_TAGS));
        }

        var results = store.search(new KnowledgeSearchRequest(
            "runA", SCHEMA, "vehicle excursion", Set.of(KnowledgeScope.RUN), 2));

        assertThat(results).hasSize(2);
    }

    @Test
    void search_emptyEntries_returnsEmpty() {
        var results = store.search(new KnowledgeSearchRequest(
            "runA", SCHEMA, "anything", Set.of(KnowledgeScope.RUN, KnowledgeScope.DATASET), 5));

        assertThat(results).isEmpty();
    }

    @Test
    void completeRun_clearsRunScopeOnly() {
        store.store(new KnowledgeEntry(
            "runA", SCHEMA, AUTHOR, "vehicle excursion finding", KnowledgeKind.FINDING,
            EnumSet.of(KnowledgeScope.RUN, KnowledgeScope.DATASET), NO_TAGS));

        store.completeRun("runA");

        var results = store.search(new KnowledgeSearchRequest(
            "runA", SCHEMA, "vehicle excursion",
            EnumSet.of(KnowledgeScope.RUN, KnowledgeScope.DATASET), 5));

        assertThat(results)
            .hasSize(1)
            .extracting(KnowledgeMatch::scope)
            .containsExactly(KnowledgeScope.DATASET);
    }

    @Test
    void entry_emptyScopes_throwsIAE() {
        assertThatThrownBy(() -> new KnowledgeEntry(
            "runA", SCHEMA, AUTHOR, "content", KnowledgeKind.FINDING, Set.of(), NO_TAGS))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void searchRequest_zeroTopK_throwsIAE() {
        assertThatThrownBy(() -> new KnowledgeSearchRequest(
            "runA", SCHEMA, "query", Set.of(KnowledgeScope.RUN), 0))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
