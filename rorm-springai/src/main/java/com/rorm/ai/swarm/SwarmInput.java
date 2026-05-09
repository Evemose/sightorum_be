package com.rorm.ai.swarm;

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Input to the durable swarm pipeline.
 * <p>
 * The model space is intentionally not part of this record: callers that
 * need it resolve via {@link com.rorm.ai.ModelSpaceResolver} from
 * {@link #schema}. Carrying it here would put it inside JobSpec args
 * shipped across Restate sub-invocations, which breaks replay-time
 * args determinism (the metamodel JSON includes UUID-based identity).
 *
 * @param userQuery the research question
 * @param schema    database schema name
 * @param anchors   explicit anchor assignments (one per generator). When
 *                  null or empty, the swarm derives anchors itself by
 *                  Jaccard-merging proposals from the scout and the
 *                  domain researcher.
 */
public record SwarmInput(
    String userQuery,
    String schema,
    @Nullable List<String> anchors
) {
}
