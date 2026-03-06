package com.rorm.dataimport.pipeline;

@FunctionalInterface
public interface ImportStep<I, O> {

    default <R> ImportStep<I, R> andThen(ImportStep<O, R> next) {
        return input -> next.execute(this.execute(input));
    }

    O execute(I input);
}
