package com.rorm.ai.swarm.agents;

import com.rorm.ai.chat.ChatRequest;
import com.rorm.ai.swarm.SwarmEvent;
import com.rorm.ai.swarm.SwarmEvent.EndEvent;
import com.rorm.ai.swarm.SwarmEvent.StartEvent;
import lombok.Builder;
import lombok.NonNull;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.publisher.Sinks.Many;

import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * Base class for swarm agent operations with shared streaming/structuring logic
 */
abstract class SwarmAgent {
    protected final SecondarySwarmAgent summarizer;

    protected SwarmAgent(SecondarySwarmAgent summarizer) {
        this.summarizer = summarizer;
    }

    protected <T> T streamAndStructurize(StepParams<T> params, Many<SwarmEvent> eventSink) {
        var tokenSink = params.tokenSink() != null ? params.tokenSink() : createTokenSink();
        var convId = UUID.randomUUID().toString();

        if (params.tokenConsumer() != null) {
            params.tokenConsumer().accept(tokenSink.asFlux());
        }
        var id = params.eventId() != null ? params.eventId() : UUID.randomUUID().toString();

        var response = params.agent.stream(params.userPrompt(), params.requestBuilderCustomizer())
            .scan(new StringBuffer(), StringBuffer::append)
            .map(StringBuffer::toString)
            .doOnSubscribe(_ -> eventSink.tryEmitNext(params.startEventFactory.apply(id, tokenSink.asFlux())))
            .doOnNext(tokenSink::tryEmitNext)
            .doOnError(tokenSink::tryEmitError)
            .doOnComplete(tokenSink::tryEmitComplete)
            .blockLast();

        var callResult = summarizer.call(response, convId, params.responseType());
        eventSink.tryEmitNext(params.endEventFactory.apply(id, callResult, response));
        return callResult;
    }

    protected Many<String> createTokenSink() {
        return Sinks.many().replay().all();
    }

    protected <T> T structurize(String response, Class<T> responseType) {
        return summarizer.call(response, responseType);
    }

    @FunctionalInterface
    protected interface TriFunction<A, B, C, R> {
        R apply(A a, B b, C c);
    }

    @Builder
    protected record StepParams<T>(
        @NonNull FirstLevelSwarmAgent agent,
        @NonNull String userPrompt,
        @NonNull Class<T> responseType,
        @NonNull BiFunction<String, Flux<String>, StartEvent> startEventFactory,
        @NonNull TriFunction<String, T, String, EndEvent<T>> endEventFactory,
        @Nullable Consumer<Flux<String>> tokenConsumer,
        @Nullable Many<String> tokenSink,
        @Nullable String eventId,
        @Nullable UnaryOperator<ChatRequest.Builder> requestBuilderCustomizer
    ) {

        @Override
        public UnaryOperator<ChatRequest.Builder> requestBuilderCustomizer() {
            return requestBuilderCustomizer != null ? requestBuilderCustomizer : UnaryOperator.identity();
        }
    }
}
