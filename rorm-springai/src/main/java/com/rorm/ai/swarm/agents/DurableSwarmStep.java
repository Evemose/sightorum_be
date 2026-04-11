package com.rorm.ai.swarm.agents;

import com.rorm.ai.chat.ChatRequest;
import com.rorm.ai.chat.StreamToken;
import com.rorm.ai.swarm.EventId;
import com.rorm.ai.swarm.SwarmEvent;
import com.rorm.ai.swarm.SwarmEvent.EndEvent;
import com.rorm.ai.swarm.SwarmEvent.StartEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks.Many;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.UnaryOperator;

/**
 * Generic step agent for the {@link com.rorm.ai.swarm.DurableSwarm} pipeline.
 * Wraps {@link FirstLevelSwarmAgent} (streaming) and {@link SecondarySwarmAgent}
 * (structurization) with event emission.
 * <p>
 * The caller passes in a pre-constructed {@link EventId} identifying this step
 * in the DAG. That id is forwarded to the start/end event factories and
 * returned in {@link StepOutput#id()} so downstream phases can reference this
 * step as a parent when building their own EventIds.
 *
 * @param <T> the DTO type this step produces
 */
public class DurableSwarmStep<T> extends SwarmAgent {

    private final FirstLevelSwarmAgent agent;
    private final Class<T> responseType;
    private final BiFunction<EventId, Flux<StreamToken>, StartEvent> startFactory;
    private final EndEventFactory<T> endFactory;

    public DurableSwarmStep(
        FirstLevelSwarmAgent agent,
        SecondarySwarmAgent summarizer,
        Class<T> responseType,
        BiFunction<EventId, Flux<StreamToken>, StartEvent> startFactory,
        EndEventFactory<T> endFactory
    ) {
        super(summarizer);
        this.agent = agent;
        this.responseType = responseType;
        this.startFactory = startFactory;
        this.endFactory = endFactory;
    }

    public StepOutput<T> execute(EventId id, String userPrompt, Many<SwarmEvent> eventSink) {
        return execute(id, userPrompt, UnaryOperator.identity(), eventSink);
    }

    public StepOutput<T> execute(
        EventId id,
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
                .eventId(id)
                .startEventFactory(startFactory)
                .endEventFactory((eid, result, raw) -> {
                    rawCapture.set(raw);
                    return endFactory.create(eid, result, raw);
                })
                .requestBuilderCustomizer(customizer)
                .build(),
            eventSink);
        return new StepOutput<>(id, dto, rawCapture.get());
    }

    @FunctionalInterface
    public interface EndEventFactory<T> {
        EndEvent<T> create(EventId id, T result, String rawResponse);
    }

    /**
     * Result of a step execution, carrying the step's EventId, the structured
     * DTO, and the raw agent response text.
     */
    public record StepOutput<T>(EventId id, T dto, String rawResponse) {}
}
