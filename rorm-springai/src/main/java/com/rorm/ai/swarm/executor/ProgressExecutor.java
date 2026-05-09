package com.rorm.ai.swarm.executor;

import com.rorm.StepJournal;
import com.rorm.ai.ModelSpaceResolver;
import com.rorm.ai.anthropic.AnthropicChatOptions.CacheTTL;
import com.rorm.ai.chat.AiChatService;
import com.rorm.ai.chat.ChatRequest;
import com.rorm.ai.chat.ThinkingLevel;
import com.rorm.ai.swarm.SwarmEventBus;
import com.rorm.ai.swarm.SwarmStreamEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Restate-dispatched per-round paraphraser. Receives one accumulated round
 * (thinking + text) from a parent agent, asks the lightweight haiku model
 * to narrate it in the configured character voice, and publishes the
 * resulting {@link SwarmStreamEvent.AgentProgress} to the event bus.
 * <p>
 * Each invocation owns its own Restate journal: the chat call is journaled
 * by {@link AiChatService}'s underlying model, and the publish is wrapped
 * in {@link StepJournal#run} so a child-invocation retry doesn't double
 * the Redis-stream entry. Parent-invocation replay is handled at the
 * dispatch layer — the parent re-issues with the same stable session
 * hash and Restate's ingress deduplicates, so this handler is only ever
 * actually executed once per round.
 */
@Slf4j
@Component("progressExecutor")
@RequiredArgsConstructor
public class ProgressExecutor {

    private static final String CHARACTER_PLACEHOLDER = "{{CHARACTER}}";
    private static final String SKIP_MARKER = "skip";
    private static final String PARAPHRASER_MODEL = "claude-haiku-4-5";
    private static final String PROMPT_RESOURCE = "prompts/durable-swarm/progress-paraphraser-system.txt";
    private static final String SYSTEM_PROMPT_TEMPLATE = loadSystemPromptTemplate();

    private final AiChatService chatService;
    private final SwarmEventBus eventBus;
    private final ModelSpaceResolver modelSpaceResolver;

    private static String loadSystemPromptTemplate() {
        try (var stream = ProgressExecutor.class.getClassLoader()
            .getResourceAsStream(PROMPT_RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException(
                    "Missing progress paraphraser prompt resource: " + PROMPT_RESOURCE);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(
                "Failed to read progress paraphraser prompt resource: " + PROMPT_RESOURCE, e);
        }
    }

    public Void process(ProgressInput input) {
        var resolved = resolveParaphrase(input, paraphrase(input));
        if (!resolved.isBlank()) {
            publish(input, resolved);
        }
        return null;
    }

    private static String resolveParaphrase(ProgressInput input, String paraphrase) {
        if (paraphrase == null || paraphrase.isBlank()
            || SKIP_MARKER.equalsIgnoreCase(paraphrase.trim())) {
            return roundIsEmpty(input) ? "" : "Working on the " + input.eventId().kind() + " task.";
        }
        return paraphrase;
    }

    private String paraphrase(ProgressInput input) {
        try {
            var modelSpace = modelSpaceResolver.resolve(input.schema());
            var system = SYSTEM_PROMPT_TEMPLATE.replace(CHARACTER_PLACEHOLDER, input.character());
            var request = ChatRequest.usingData(input.schema(), modelSpace)
                .withSystemPrompt(system)
                .withModelName(PARAPHRASER_MODEL)
                .withThinkingLevel(ThinkingLevel.NONE)
                .withCachingStrategyFunction(_ -> CacheTTL.NONE)
                .ask(buildUserPrompt(input), String.class);
            return chatService.call(request);
        } catch (Exception e) {
            log.warn("[progress-advisor] paraphrase failed for {} ({}): {}",
                input.eventId().kind(), input.eventId().shortToken(), e.toString());
            return null;
        }
    }

    private void publish(ProgressInput input, String resolved) {
        StepJournal.current().run("publish-progress", String.class, () -> {
            eventBus.publish(input.runId(),
                new SwarmStreamEvent.AgentProgress(
                    input.eventId(), input.priorRounds().size(), resolved));
            return resolved;
        });
    }

    private static boolean roundIsEmpty(ProgressInput input) {
        return input.thinking().isBlank() && input.text().isBlank();
    }

    private static String buildUserPrompt(ProgressInput input) {
        var sb = new StringBuilder();
        sb.append("AGENT_KIND: ").append(input.eventId().kind()).append("\n\n");
        var prior = input.priorRounds();
        sb.append("PRIOR_ROUNDS (count = ").append(prior.size())
            .append(", already narrated by you in earlier lines):\n");
        if (prior.isEmpty()) {
            sb.append("(none — this is the OPENING line of the monologue)\n\n");
        } else {
            for (var i = 0; i < prior.size(); i++) {
                sb.append("--- round ").append(i + 1).append(" ---\n");
                if (!prior.get(i).thinking().isBlank()) {
                    sb.append("THINKING:\n").append(prior.get(i).thinking()).append('\n');
                }
                if (!prior.get(i).text().isBlank()) {
                    sb.append("TEXT:\n").append(prior.get(i).text()).append('\n');
                }
            }
            sb.append("\nYou are now writing line ").append(prior.size() + 1)
                .append(" of this same monologue. Do NOT re-open; continue mid-stream.\n\n");
        }
        sb.append("CURRENT_ROUND (the round you must paraphrase now):\n");
        if (!input.thinking().isBlank()) {
            sb.append("THINKING:\n").append(input.thinking()).append("\n\n");
        }
        if (!input.text().isBlank()) {
            sb.append("TEXT:\n").append(input.text()).append("\n");
        }
        if (roundIsEmpty(input)) {
            sb.append("(empty)\n");
        }
        return sb.toString();
    }
}
