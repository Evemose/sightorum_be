package com.rorm.ai.swarm;

import com.rorm.ai.swarm.dto.ProposedAnchor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Stream;

/**
 * Deterministic Jaccard-based merge of two anchor proposal lists (scout +
 * domain researcher). For each scout anchor, finds the best entity-set
 * overlap among the domain anchors; if the overlap is at or above the
 * threshold, concatenates the two anchors into a single 'merged' anchor
 * (union of entities, joined name/perspective/rationale). Anchors that
 * match nothing on the other side are kept solo and tagged with their
 * source. Greedy bipartite matching: each domain anchor can be matched
 * at most once.
 * <p>
 * No LLM call: this is a runtime-side reconciliation, intentionally
 * deterministic so the same proposals always produce the same merge.
 * The trade-off is that semantically-equivalent anchors using different
 * entity vocabularies (e.g. {@code containers} vs {@code packaging_units})
 * won't merge — call sites that need true semantic merging should
 * sharpen the proposers' entity vocabulary first, not bolt an LLM here.
 */
public final class AnchorReconciler {

    private static final double JACCARD_THRESHOLD = 0.5;

    private AnchorReconciler() {
    }

    public static List<ProposedAnchor> merge(List<ProposedAnchor> scoutAnchors,
                                             List<ProposedAnchor> domainAnchors) {
        var scout = scoutAnchors == null ? List.<ProposedAnchor>of() : scoutAnchors;
        var domain = domainAnchors == null ? List.<ProposedAnchor>of() : domainAnchors;
        var result = new ArrayList<ProposedAnchor>();
        var domainPool = new ArrayList<>(domain);

        for (var scoutAnchor : scout) {
            var match = bestMatch(scoutAnchor, domainPool);
            if (match != null && match.score() >= JACCARD_THRESHOLD) {
                result.add(combine(scoutAnchor, match.anchor()));
                domainPool.remove(match.anchor());
            } else {
                result.add(tagSource(scoutAnchor, "scout"));
            }
        }
        for (var leftover : domainPool) {
            result.add(tagSource(leftover, "domain"));
        }
        return result;
    }

    private static Match bestMatch(ProposedAnchor anchor, List<ProposedAnchor> pool) {
        Match best = null;
        for (var candidate : pool) {
            var score = jaccard(anchor.entities(), candidate.entities());
            if (best == null || score > best.score()) {
                best = new Match(candidate, score);
            }
        }
        return best;
    }

    private static ProposedAnchor combine(ProposedAnchor scout, ProposedAnchor domain) {
        var entities = Stream.concat(scout.entities().stream(), domain.entities().stream())
            .distinct()
            .toList();
        return new ProposedAnchor(
            scout.name() + " | " + domain.name(),
            entities,
            scout.perspective() + "; " + domain.perspective(),
            scout.rationale() + "; " + domain.rationale(),
            "merged");
    }

    private static ProposedAnchor tagSource(ProposedAnchor anchor, String source) {
        return new ProposedAnchor(
            anchor.name(),
            anchor.entities(),
            anchor.perspective(),
            anchor.rationale(),
            source);
    }

    private static double jaccard(List<String> a, List<String> b) {
        var setA = a == null ? new HashSet<String>() : new HashSet<>(a);
        var setB = b == null ? new HashSet<String>() : new HashSet<>(b);
        if (setA.isEmpty() && setB.isEmpty()) {
            return 0.0;
        }
        var union = new HashSet<>(setA);
        union.addAll(setB);
        long intersection = setA.stream().filter(setB::contains).count();
        return (double) intersection / union.size();
    }

    private record Match(ProposedAnchor anchor, double score) {}
}
