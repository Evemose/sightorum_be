package com.rorm.ai.swarm.agents;

import com.rorm.ai.chat.ChatRequest;
import com.rorm.ai.chat.StreamToken;
import com.rorm.ai.swarm.EventId;
import com.rorm.ai.swarm.SwarmEvent;
import com.rorm.ai.swarm.SwarmEvent.EndEvent;
import com.rorm.ai.swarm.SwarmEvent.StartEvent;
import lombok.Builder;
import lombok.NonNull;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.publisher.Sinks.Many;

import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

abstract class SwarmAgent {
    protected final SecondarySwarmAgent summarizer;

    protected SwarmAgent(SecondarySwarmAgent summarizer) {
        this.summarizer = summarizer;
    }

    protected <T> T streamAndStructurize(StepParams<T> params, Many<SwarmEvent> eventSink) {
        var ownsTokenSink = params.tokenSink() == null;
        var tokenSink = ownsTokenSink ? createTokenSink() : params.tokenSink();

        if (params.tokenConsumer() != null) {
            params.tokenConsumer().accept(tokenSink.asFlux());
        }
        var id = params.eventId();

        var response = params.agent.streamTokens(params.userPrompt(), params.requestBuilderCustomizer())
            .doOnSubscribe(_ -> eventSink.tryEmitNext(params.startEventFactory.apply(id, tokenSink.asFlux())))
            .doOnNext(tokenSink::tryEmitNext)
            .doOnError(tokenSink::tryEmitError)
            .doOnComplete(() -> {
                if (ownsTokenSink) {
                    tokenSink.tryEmitComplete();
                }
            })
            .reduce(new StringBuilder(), (sb, token) -> sb.append(token.toText()))
            .map(StringBuilder::toString)
            .block();

        var callResult = summarizer.call(response, null, params.responseType());
        eventSink.tryEmitNext(params.endEventFactory.apply(id, callResult, response));
        return callResult;
    }

    protected Many<StreamToken> createTokenSink() {
        return Sinks.many().replay().all();
    }

    protected <T> T structurize(String response, Class<T> responseType) {
        return summarizer.call(response, responseType);
    }

    @FunctionalInterface
    public interface TriFunction<A, B, C, R> {
        R apply(A a, B b, C c);
    }

    @Builder
    protected record StepParams<T>(
        @NonNull FirstLevelSwarmAgent agent,
        @NonNull String userPrompt,
        @NonNull Class<T> responseType,
        @NonNull EventId eventId,
        @NonNull BiFunction<EventId, Flux<StreamToken>, StartEvent> startEventFactory,
        @NonNull TriFunction<EventId, T, String, EndEvent<T>> endEventFactory,
        @Nullable Consumer<Flux<StreamToken>> tokenConsumer,
        @Nullable Many<StreamToken> tokenSink,
        @Nullable UnaryOperator<ChatRequest.Builder> requestBuilderCustomizer
    ) {

        @Override
        public UnaryOperator<ChatRequest.Builder> requestBuilderCustomizer() {
            return requestBuilderCustomizer != null ? requestBuilderCustomizer : UnaryOperator.identity();
        }
    }
}
