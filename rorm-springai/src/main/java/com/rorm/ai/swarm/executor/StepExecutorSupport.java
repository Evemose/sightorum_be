package com.rorm.ai.swarm.executor;

import com.rorm.ai.chat.AiChatService;
import com.rorm.ai.chat.ChatRequest;
import com.rorm.ai.chat.MemoryInclude;
import com.rorm.ai.prompt.PromptPlaceholders;
import com.rorm.ai.swarm.*;
import com.rorm.ai.swarm.agents.FirstLevelSwarmAgent;
import com.rorm.ai.swarm.agents.SecondarySwarmAgent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.function.UnaryOperator;

/**
 * Shared execution logic for all step executor beans: streams LLM tokens,
 * publishes them to the {@link SwarmEventBus}, accumulates raw text, and
 * structurizes the final DTO. Agent kind for event publishing is taken from
 * {@link StepExecutionInput#eventId()} — phases assign it when creating the
 * EventId, so support does not need it as a separate parameter.
 */
@Component
@RequiredArgsConstructor
public class StepExecutorSupport {

    private final AiChatService chatService;
    private final PromptPlaceholders promptPlaceholders;
    private final DurableSwarmConfig config;
    private final SwarmEventBus eventBus;

    public <T> StepOutput<T> execute(StepExecutionInput input, AgentModelConfig agentConfig,
                                     Class<T> responseType) {
        var kind = input.eventId().kind();
        eventBus.publish(input.runId(), new SwarmStreamEvent.AgentStarted(input.eventId(), kind));
        var effectiveConfig = input.systemPromptOverride() != null
            ? agentConfig.withSystemPrompt(input.systemPromptOverride())
            : agentConfig;
        var raw = streamRaw(input, effectiveConfig);
        var dto = summarize(input, raw, responseType);
        eventBus.publish(input.runId(), new SwarmStreamEvent.AgentFinished(input.eventId(), kind, raw));
        return new StepOutput<>(input.eventId(), dto, raw);
    }

    private String streamRaw(StepExecutionInput input, AgentModelConfig agentConfig) {
        var agent = new FirstLevelSwarmAgent(
            agentConfig, chatService, input.schema(), input.modelSpace(), promptPlaceholders);
        var runId = input.runId();
        var eventId = input.eventId();
        return agent.streamTokens(input.userPrompt(), customizerFrom(input))
            .doOnNext(token -> eventBus.publish(runId, new SwarmStreamEvent.AgentToken(eventId, token)))
            .reduce(new StringBuilder(), (sb, token) -> sb.append(token.toText()))
            .map(StringBuilder::toString)
            .block();
    }

    private <T> T summarize(StepExecutionInput input, String raw, Class<T> responseType) {
        var summarizer = new SecondarySwarmAgent(
            config.summarizer(), chatService, input.schema(), input.modelSpace(), promptPlaceholders);
        return summarizer.call(raw, null, responseType);
    }

    private static UnaryOperator<ChatRequest.Builder> customizerFrom(StepExecutionInput input) {
        return b -> {
            if (input.chatId() != null) {
                b = b.withChatId(input.chatId());
            }
            if (input.memoryIncludes() != null && !input.memoryIncludes().isEmpty()) {
                b = b.withMemoryIncludes(input.memoryIncludes().toArray(MemoryInclude[]::new));
            }
            if (input.toolContextEntries() != null) {
                for (var entry : input.toolContextEntries().entrySet()) {
                    b = b.withToolContextEntry(entry.getKey(), entry.getValue());
                }
            }
            return b;
        };
    }
}
