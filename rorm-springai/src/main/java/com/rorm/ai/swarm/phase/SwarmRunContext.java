package com.rorm.ai.swarm.phase;

import com.rorm.StepJournal;
import com.rorm.ai.chat.AiChatService;
import com.rorm.ai.chat.ChatRequest;
import com.rorm.ai.chat.StreamToken;
import com.rorm.ai.prompt.PromptPlaceholders;
import com.rorm.ai.swarm.AgentModelConfig;
import com.rorm.ai.swarm.EventId;
import com.rorm.ai.swarm.SwarmEvent;
import com.rorm.ai.swarm.SwarmInput;
import com.rorm.ai.swarm.agents.DurableSwarmStep;
import com.rorm.ai.swarm.agents.FirstLevelSwarmAgent;
import com.rorm.ai.swarm.agents.SecondarySwarmAgent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.function.BiFunction;
import java.util.function.UnaryOperator;

public record SwarmRunContext(
    SwarmInput input,
    StepJournal journal,
    Sinks.Many<SwarmEvent> events,
    SecondarySwarmAgent summarizer,
    AiChatService chatService,
    PromptPlaceholders promptPlaceholders
) {

    public static UnaryOperator<ChatRequest.Builder> withChatId(String chatId) {
        return b -> b.withChatId(chatId);
    }

    public <T> DurableSwarmStep<T> step(
        AgentModelConfig config, Class<T> responseType,
        BiFunction<EventId, Flux<StreamToken>, SwarmEvent.StartEvent> startFactory,
        DurableSwarmStep.EndEventFactory<T> endFactory
    ) {
        var agent = new FirstLevelSwarmAgent(
            config, chatService, input.schema(), input.modelSpace(), promptPlaceholders);
        return new DurableSwarmStep<>(agent, summarizer, responseType, startFactory, endFactory);
    }
}
