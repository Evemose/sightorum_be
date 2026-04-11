package com.rorm.ai.swarm.dto;

/**
 * Shared deterministic slicer: given a raw source text and two short line
 * anchors (firstLine, lastLine), returns the substring that begins at the
 * first occurrence of firstLine and ends immediately after the first
 * occurrence of lastLine that follows it.
 *
 * <p>Used by {@link HypothesisGenerationDTO.Hypothesis} and every global
 * record ({@link HypothesisGenerationDTO.BelowDetectionThreshold},
 * {@link HypothesisGenerationDTO.DomainDiscrepancy},
 * {@link HypothesisGenerationDTO.RareEventFinding},
 * {@link HypothesisGenerationDTO.InteractionCandidate}) to cut their raw
 * blocks out of the rebuttal source without going through any lossy
 * structured rendering.
 */
final class Slicer {

    private Slicer() {
    }

    static String slice(String rawSource, String firstLine, String lastLine, String label) {
        var start = rawSource.indexOf(firstLine);
        if (start < 0) {
            throw new IllegalArgumentException(
                "firstLine anchor not found in raw source for " + label + ": " + firstLine);
        }
        var lastLineStart = rawSource.indexOf(lastLine, start);
        if (lastLineStart < 0) {
            throw new IllegalArgumentException(
                "lastLine anchor not found after firstLine for " + label + ": " + lastLine);
        }
        return rawSource.substring(start, lastLineStart + lastLine.length());
    }
}
