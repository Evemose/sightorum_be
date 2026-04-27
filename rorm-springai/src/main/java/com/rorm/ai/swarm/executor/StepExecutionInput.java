package com.rorm.ai.swarm.executor;

import com.rorm.ai.chat.MemoryInclude;
import com.rorm.ai.swarm.EventId;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.Set;

/**
 * Serializable input envelope for all step executor beans. Carries
 * per-invocation data across the durable boundary; Spring beans (chat
 * service, event bus, config) are injected into the executor itself.
 * <p>
 * The {@code ModelSpace} is intentionally absent — the executor re-resolves
 * it from {@link #schema} on receipt via
 * {@link com.rorm.ai.ModelSpaceResolver}. Shipping {@code ModelSpace}
 * through Restate JobSpec args breaks replay-time call-args determinism
 * because its serialization includes UUID-based {@code @JsonIdentityInfo}
 * for cycle resolution.
 *
 * @param eventId              DAG identity for this step (parent links, tags, kind)
 * @param userPrompt           fully-resolved user prompt (no remaining placeholders)
 * @param schema               data schema name; the executor resolves the
 *                             matching {@code ModelSpace} from this
 * @param runId                shared swarm run identifier for the event bus stream
 * @param chatId               optional conversation ID for multi-turn steps (generator → rebuttal)
 * @param memoryIncludes       optional memory context to include in the chat request
 * @param toolContextEntries   extra key-value pairs injected into the ToolContext for this step's tools
 * @param systemPromptOverride optional override for the executor's configured system prompt,
 *                             already rendered for per-invocation placeholders that the
 *                             agent-level renderer does not know about (e.g. {@code {{HYPOTHESIS}}})
 */
public record StepExecutionInput(
    EventId eventId,
    String userPrompt,
    String schema,
    String runId,
    @Nullable String chatId,
    @Nullable Set<MemoryInclude> memoryIncludes,
    @Nullable Map<String, Object> toolContextEntries,
    @Nullable String systemPromptOverride
) {

    public StepExecutionInput(EventId eventId, String userPrompt, String schema, String runId) {
        this(eventId, userPrompt, schema, runId, null, null, null, null);
    }

    public StepExecutionInput(EventId eventId, String userPrompt, String schema, String runId,
                              @Nullable String chatId, @Nullable Set<MemoryInclude> memoryIncludes) {
        this(eventId, userPrompt, schema, runId, chatId, memoryIncludes, null, null);
    }

    public StepExecutionInput(EventId eventId, String userPrompt, String schema, String runId,
                              @Nullable String chatId, @Nullable Set<MemoryInclude> memoryIncludes,
                              @Nullable Map<String, Object> toolContextEntries) {
        this(eventId, userPrompt, schema, runId, chatId, memoryIncludes, toolContextEntries, null);
    }
}
