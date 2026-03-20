package com.rorm.ml.restate;

import com.rorm.DurableFuture;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

sealed interface RestateDurableFuture<T> extends DurableFuture<T> {

    String id();

    @Override
    default DurableFuture<Void> combineAll(DurableFuture<?>... futures) {
        return new Combined<>(toRestate(futures), UUID.randomUUID().toString());
    }

    @SuppressWarnings("unchecked")
    private static List<RestateDurableFuture<Void>> toRestate(DurableFuture<?>... futures) {
        return Arrays.stream(futures)
            .map(f -> (RestateDurableFuture<Void>) f)
            .toList();
    }

    record Resolved<T>(T value, String id) implements RestateDurableFuture<T> {

        @Override
        public T await() {
            return value;
        }

        @Override
        public <U> DurableFuture<U> map(Function<T, U> mapper) {
            return new Resolved<>(mapper.apply(value), UUID.randomUUID().toString());
        }
    }

    record Delegated<T>(dev.restate.sdk.DurableFuture<T> delegate, String id) implements RestateDurableFuture<T> {

        @Override
        public T await() {
            return delegate.await();
        }

        @Override
        public <U> DurableFuture<U> map(Function<T, U> mapper) {
            return new Delegated<>(delegate.map(mapper::apply), UUID.randomUUID().toString());
        }
    }

    record Combined<T>(
        List<RestateDurableFuture<T>> children,
        String id
    ) implements RestateDurableFuture<T> {

        @Override
        public <U> DurableFuture<U> map(Function<T, U> mapper) {
            return new Resolved<>(mapper.apply(await()), UUID.randomUUID().toString());
        }

        @Override
        public T await() {
            var delegated = children.stream()
                .filter(Delegated.class::isInstance)
                .<dev.restate.sdk.DurableFuture<?>>map(f -> ((Delegated<?>) f).delegate())
                .toList();
            if (!delegated.isEmpty()) {
                dev.restate.sdk.DurableFuture.all(delegated).await();
            }
            return null;
        }
    }

}