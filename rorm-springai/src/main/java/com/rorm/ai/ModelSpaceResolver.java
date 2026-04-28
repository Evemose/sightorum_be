package com.rorm.ai;

import com.rorm.metamodel.ModelSpace;

/**
 * SPI for resolving a {@link ModelSpace} from a schema name. The
 * springai module needs the model space to render prompts and run
 * agents, but does not own the metamodel registry — downstream modules
 * (typically rorm-client) provide the implementation.
 * <p>
 * This indirection is required because {@link ModelSpace} cannot be
 * shipped through Restate JobSpec args: its serialization includes
 * non-deterministic per-run identity (UUID-based {@code @JsonIdentityInfo}),
 * which breaks Restate's replay-time call-args byte comparison.
 * Carrying only the schema name keeps the JobSpec deterministic; the
 * receiving executor re-resolves the model space inside its own context.
 */
@FunctionalInterface
public interface ModelSpaceResolver {

    ModelSpace resolve(String schema);
}
