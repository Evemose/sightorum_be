package com.rorm.ai.swarm;

import com.rorm.ai.swarm.dto.ProposedAnchor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AnchorReconciler — Jaccard merge")
class AnchorReconcilerTest {

    private static ProposedAnchor anchor(String name, String... entities) {
        return new ProposedAnchor(name, List.of(entities),
            "perspective for " + name, "rationale for " + name, null);
    }

    @Nested
    @DisplayName("empty / null inputs")
    class EmptyAndNull {

        @Test
        void bothEmpty_returnsEmpty() {
            assertThat(AnchorReconciler.merge(List.of(), List.of())).isEmpty();
        }

        @Test
        void bothNull_returnsEmpty() {
            assertThat(AnchorReconciler.merge(null, null)).isEmpty();
        }

        @Test
        void scoutNullDomainPresent_keepsDomainSolo() {
            var d = anchor("Domain frame", "alpha");
            var result = AnchorReconciler.merge(null, List.of(d));
            assertThat(result).singleElement().satisfies(a -> {
                assertThat(a.name()).isEqualTo("Domain frame");
                assertThat(a.source()).isEqualTo("domain");
                assertThat(a.entities()).containsExactly("alpha");
            });
        }

        @Test
        void domainNullScoutPresent_keepsScoutSolo() {
            var s = anchor("Scout frame", "alpha");
            var result = AnchorReconciler.merge(List.of(s), null);
            assertThat(result).singleElement().satisfies(a -> {
                assertThat(a.name()).isEqualTo("Scout frame");
                assertThat(a.source()).isEqualTo("scout");
            });
        }
    }

    @Nested
    @DisplayName("single-side proposals")
    class SingleSide {

        @Test
        void onlyScout_allTaggedScout() {
            var s1 = anchor("S1", "a");
            var s2 = anchor("S2", "b", "c");
            var result = AnchorReconciler.merge(List.of(s1, s2), List.of());
            assertThat(result).hasSize(2)
                .allSatisfy(a -> assertThat(a.source()).isEqualTo("scout"));
            assertThat(result).extracting(ProposedAnchor::name)
                .containsExactly("S1", "S2");
        }

        @Test
        void onlyDomain_allTaggedDomain() {
            var d1 = anchor("D1", "a");
            var d2 = anchor("D2", "b");
            var result = AnchorReconciler.merge(List.of(), List.of(d1, d2));
            assertThat(result).hasSize(2)
                .allSatisfy(a -> assertThat(a.source()).isEqualTo("domain"));
        }
    }

    @Nested
    @DisplayName("Jaccard threshold (0.5)")
    class JaccardThreshold {

        @Test
        void identicalEntities_merges() {
            var s = anchor("Equipment capital stock", "containers", "vehicles");
            var d = anchor("Asset durability", "containers", "vehicles");
            var result = AnchorReconciler.merge(List.of(s), List.of(d));
            assertThat(result).singleElement().satisfies(a -> {
                assertThat(a.source()).isEqualTo("merged");
                assertThat(a.name()).isEqualTo("Equipment capital stock | Asset durability");
                assertThat(a.entities()).containsExactlyInAnyOrder("containers", "vehicles");
            });
        }

        @Test
        void disjointEntities_keepsBothSolo() {
            var s = anchor("Scout", "a", "b");
            var d = anchor("Domain", "x", "y");
            var result = AnchorReconciler.merge(List.of(s), List.of(d));
            assertThat(result).hasSize(2)
                .extracting(ProposedAnchor::source)
                .containsExactlyInAnyOrder("scout", "domain");
        }

        @Test
        void overlapAtThreshold_merges() {
            // 2 of 3 entities overlap → Jaccard = 2/3 ≈ 0.667 ≥ 0.5
            var s = anchor("Scout", "a", "b");
            var d = anchor("Domain", "a", "b", "c");
            var result = AnchorReconciler.merge(List.of(s), List.of(d));
            assertThat(result).singleElement().satisfies(a ->
                assertThat(a.source()).isEqualTo("merged"));
        }

        @Test
        void overlapBelowThreshold_keepsSolo() {
            // 1 of 4 entities overlap → Jaccard = 1/4 = 0.25 < 0.5
            var s = anchor("Scout", "a", "b");
            var d = anchor("Domain", "a", "x", "y");
            var result = AnchorReconciler.merge(List.of(s), List.of(d));
            assertThat(result).hasSize(2);
        }

        @Test
        void exactly0_5_merges() {
            // |{a}| / |{a,b}| = 1/2 = 0.5 exactly (boundary; >= threshold merges)
            var s = anchor("Scout", "a", "b");
            var d = anchor("Domain", "a");
            var result = AnchorReconciler.merge(List.of(s), List.of(d));
            assertThat(result).singleElement().satisfies(a ->
                assertThat(a.source()).isEqualTo("merged"));
        }
    }

    @Nested
    @DisplayName("merge concatenation")
    class MergeConcatenation {

        @Test
        void mergedFields_areConcatenated() {
            var s = new ProposedAnchor("Scout name", List.of("x", "y"),
                "Scout perspective", "Scout rationale", null);
            var d = new ProposedAnchor("Domain name", List.of("x", "y"),
                "Domain perspective", "Domain rationale", null);
            var result = AnchorReconciler.merge(List.of(s), List.of(d));
            assertThat(result).singleElement().satisfies(a -> {
                assertThat(a.name()).isEqualTo("Scout name | Domain name");
                assertThat(a.perspective()).isEqualTo("Scout perspective; Domain perspective");
                assertThat(a.rationale()).isEqualTo("Scout rationale; Domain rationale");
                assertThat(a.source()).isEqualTo("merged");
            });
        }

        @Test
        void mergedEntities_areUnionedAndDistinct() {
            var s = anchor("S", "a", "b", "c");
            var d = anchor("D", "b", "c", "d");
            // Jaccard 2/4 = 0.5 → merges
            var result = AnchorReconciler.merge(List.of(s), List.of(d));
            assertThat(result).singleElement().satisfies(a ->
                assertThat(a.entities())
                    .containsExactlyInAnyOrder("a", "b", "c", "d")
                    .doesNotHaveDuplicates());
        }

        @Test
        void scoutOrderPreserved_inMergedEntityList() {
            // |{a,b}| / |{a,b,c}| = 2/3 ≈ 0.67 → merges. Scout entities come
            // first in the merged list (insertion order via Stream.distinct()),
            // then domain-unique entities ("c").
            var s = anchor("S", "a", "b");
            var d = anchor("D", "a", "b", "c");
            var result = AnchorReconciler.merge(List.of(s), List.of(d));
            assertThat(result.getFirst().entities())
                .containsExactly("a", "b", "c");
        }
    }

    @Nested
    @DisplayName("greedy bipartite matching")
    class GreedyMatching {

        @Test
        void firstScoutClaims_bestDomainMatch() {
            // Both scouts overlap entirely with d. Greedy: s1 takes d, s2 stays solo.
            var s1 = anchor("S1", "a", "b");
            var s2 = anchor("S2", "a", "b");
            var d = anchor("D", "a", "b");
            var result = AnchorReconciler.merge(List.of(s1, s2), List.of(d));
            assertThat(result).hasSize(2);
            // First entry: s1 merged with d
            assertThat(result.get(0).source()).isEqualTo("merged");
            assertThat(result.get(0).name()).contains("S1").contains("D");
            // Second entry: s2 solo
            assertThat(result.get(1).source()).isEqualTo("scout");
            assertThat(result.get(1).name()).isEqualTo("S2");
        }

        @Test
        void domainAnchorMatchedOnce_remainingDomainKeptSolo() {
            var s = anchor("S", "a", "b");
            var d1 = anchor("D1", "a", "b");
            var d2 = anchor("D2", "x", "y");
            var result = AnchorReconciler.merge(List.of(s), List.of(d1, d2));
            assertThat(result).hasSize(2);
            assertThat(result).extracting(ProposedAnchor::source)
                .containsExactly("merged", "domain");
        }

        @Test
        void picksHighestJaccardAmongDomainCandidates() {
            // s overlaps fully with d2 (1.0), partially with d1 (0.5).
            // Greedy should pick d2 (higher Jaccard) for s.
            var s = anchor("S", "a", "b");
            var d1 = anchor("D1", "a", "c");           // J = 1/3 ≈ 0.33 (below threshold)
            var d2 = anchor("D2", "a", "b");           // J = 1.0
            var result = AnchorReconciler.merge(List.of(s), List.of(d1, d2));
            assertThat(result).hasSize(2);
            // s+d2 merged
            assertThat(result.get(0).source()).isEqualTo("merged");
            assertThat(result.get(0).name()).contains("D2");
            // d1 left solo
            assertThat(result.get(1).source()).isEqualTo("domain");
            assertThat(result.get(1).name()).isEqualTo("D1");
        }
    }

    @Nested
    @DisplayName("source tagging")
    class SourceTagging {

        @Test
        void unmergedScout_taggedScout() {
            var s = anchor("S", "a");
            var d = anchor("D", "x");
            var result = AnchorReconciler.merge(List.of(s), List.of(d));
            assertThat(result).filteredOn(a -> a.name().equals("S"))
                .singleElement().extracting(ProposedAnchor::source).isEqualTo("scout");
        }

        @Test
        void unmergedDomain_taggedDomain() {
            var s = anchor("S", "a");
            var d = anchor("D", "x");
            var result = AnchorReconciler.merge(List.of(s), List.of(d));
            assertThat(result).filteredOn(a -> a.name().equals("D"))
                .singleElement().extracting(ProposedAnchor::source).isEqualTo("domain");
        }

        @Test
        void mergedAnchor_taggedMerged() {
            var s = anchor("S", "a", "b");
            var d = anchor("D", "a", "b");
            var result = AnchorReconciler.merge(List.of(s), List.of(d));
            assertThat(result).singleElement()
                .extracting(ProposedAnchor::source).isEqualTo("merged");
        }

        @Test
        void inboundSourceField_isOverwritten() {
            // proposers may leave source null OR set it; reconciler is authoritative
            var s = new ProposedAnchor("S", List.of("a"),
                "p", "r", "stale_source");
            var result = AnchorReconciler.merge(List.of(s), List.of());
            assertThat(result).singleElement()
                .extracting(ProposedAnchor::source).isEqualTo("scout");
        }
    }

    @Nested
    @DisplayName("edge cases")
    class EdgeCases {

        @Test
        void emptyEntityLists_jaccardZero_keepsSolo() {
            var s = new ProposedAnchor("S", List.of(), "p", "r", null);
            var d = new ProposedAnchor("D", List.of(), "p", "r", null);
            var result = AnchorReconciler.merge(List.of(s), List.of(d));
            // Both empty → Jaccard = 0 → no merge
            assertThat(result).hasSize(2);
        }

        @Test
        void singleEntityIdentical_merges() {
            var s = anchor("S", "only");
            var d = anchor("D", "only");
            var result = AnchorReconciler.merge(List.of(s), List.of(d));
            assertThat(result).singleElement()
                .extracting(ProposedAnchor::source).isEqualTo("merged");
        }

        @Test
        void duplicateEntitiesWithinAnchor_handled() {
            // Entity list contains duplicates within one anchor — Jaccard treats them as set
            var s = new ProposedAnchor("S", List.of("a", "a", "b"), "p", "r", null);
            var d = new ProposedAnchor("D", List.of("a", "b"), "p", "r", null);
            var result = AnchorReconciler.merge(List.of(s), List.of(d));
            // Set semantics: Jaccard = |{a,b}| / |{a,b}| = 1.0 → merge
            assertThat(result).singleElement().satisfies(a -> {
                assertThat(a.source()).isEqualTo("merged");
                assertThat(a.entities()).containsExactlyInAnyOrder("a", "b");
            });
        }
    }
}
