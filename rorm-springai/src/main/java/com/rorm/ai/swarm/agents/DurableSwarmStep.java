package com.rorm.ai.swarm.agents;

import com.rorm.ai.chat.ChatRequest;
import com.rorm.ai.swarm.SwarmEvent;
import com.rorm.ai.swarm.SwarmEvent.EndEvent;
import com.rorm.ai.swarm.SwarmEvent.StartEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks.Many;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.UnaryOperator;

/**
 * Generic step agent for the {@link com.rorm.ai.swarm.DurableSwarm} pipeline.
 * <p>
 * Wraps a {@link FirstLevelSwarmAgent} (streaming) + {@link SecondarySwarmAgent}
 * (structurization) with event emission, following the same
 * {@link SwarmAgent#streamAndStructurize} pattern as the original Swarm agents.
 *
 * @param <T> the DTO type this step produces
 */
public class DurableSwarmStep<T> extends SwarmAgent {

    private final FirstLevelSwarmAgent agent;
    private final Class<T> responseType;
    private final String eventIdPrefix;
    private final BiFunction<String, Flux<String>, StartEvent> startFactory;
    private final EndEventFactory<T> endFactory;
    public DurableSwarmStep(
        FirstLevelSwarmAgent agent,
        SecondarySwarmAgent summarizer,
        Class<T> responseType,
        String eventIdPrefix,
        BiFunction<String, Flux<String>, StartEvent> startFactory,
        EndEventFactory<T> endFactory
    ) {
        super(summarizer);
        this.agent = agent;
        this.responseType = responseType;
        this.eventIdPrefix = eventIdPrefix;
        this.startFactory = startFactory;
        this.endFactory = endFactory;
    }

    /**
     * Execute the agent step: stream → collect → structurize → emit events.
     */
    public StepOutput<T> execute(String userPrompt, Many<SwarmEvent> eventSink) {
        return execute(userPrompt, UnaryOperator.identity(), eventSink);
    }

    /**
     * Execute with a request customizer (e.g. to set chatId, toolGroups, etc.).
     */
    public StepOutput<T> execute(
        String userPrompt,
        UnaryOperator<ChatRequest.Builder> customizer,
        Many<SwarmEvent> eventSink
    ) {
        var rawCapture = new AtomicReference<String>();
        var dto = streamAndStructurize(
            StepParams.<T>builder()
                .agent(agent)
                .userPrompt(userPrompt)
                .responseType(responseType)
                .eventId(eventIdPrefix + "-" + UUID.randomUUID().toString().substring(0, 8))
                .startEventFactory(startFactory)
                .endEventFactory((id, result, raw) -> {
                    rawCapture.set(raw);
                    return endFactory.create(id, result, raw);
                })
                .requestBuilderCustomizer(customizer)
                .build(),
            eventSink);
        return new StepOutput<>(dto, rawCapture.get());
    }

    @FunctionalInterface
    public interface EndEventFactory<T> {
        EndEvent<T> create(String id, T result, String rawResponse);
    }

    /**
     * Result of a step execution — carries both the structured DTO and the raw
     * agent response (needed for downstream prompt construction).
     */
    public record StepOutput<T>(T dto, String rawResponse) {}
}
