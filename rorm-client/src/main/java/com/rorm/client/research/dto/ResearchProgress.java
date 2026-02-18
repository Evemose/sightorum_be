package com.rorm.client.research.dto;

import java.util.UUID;

/**
 * SSE event types for research streaming.
 */
public sealed interface ResearchProgress {

    UUID researchId();

    record NodeStarted(
        UUID researchId,
        String nodeId,
        String nodeType
    ) implements ResearchProgress {}

    record Tokens(
        UUID researchId,
        String nodeId,
        String text
    ) implements ResearchProgress {}

    record NodeFinished(
        UUID researchId,
        String nodeId,
        String nodeType,
        Object findings
    ) implements ResearchProgress {}

    record ResearchComplete(
        UUID researchId
    ) implements ResearchProgress {}

    record ResearchFailed(
        UUID researchId,
        String error
    ) implements ResearchProgress {}
}
