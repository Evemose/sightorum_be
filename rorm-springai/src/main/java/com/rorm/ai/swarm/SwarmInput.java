package com.rorm.ai.swarm;

import com.rorm.metamodel.ModelSpace;

import java.util.List;

/**
 * Input to the durable swarm pipeline.
 *
 * @param userQuery  the research question
 * @param schema     database schema name
 * @param modelSpace resolved metamodel
 * @param anchors    one anchor assignment per generator (drives fanout)
 */
public record SwarmInput(
    String userQuery,
    String schema,
    ModelSpace modelSpace,
    List<String> anchors
) {
}
