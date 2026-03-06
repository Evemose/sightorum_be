package com.rorm.dataimport.pipeline;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImportStepTest {

    @Test
    void executeSingleStep() {
        ImportStep<String, Integer> step = String::length;

        assertThat(step.execute("hello")).isEqualTo(5);
    }

    @Test
    void andThenComposesSteps() {
        ImportStep<String, Integer> length = String::length;
        ImportStep<Integer, String> toString = i -> "len=" + i;

        var composed = length.andThen(toString);

        assertThat(composed.execute("hello")).isEqualTo("len=5");
    }

    @Test
    void andThenPropagatesExceptions() {
        ImportStep<String, Integer> failing = _ -> {
            throw new IllegalStateException("boom");
        };
        ImportStep<Integer, String> next = Object::toString;

        var composed = failing.andThen(next);

        assertThatThrownBy(() -> composed.execute("test"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("boom");
    }

    @Test
    void threeStepChain() {
        ImportStep<String, String[]> split = s -> s.split(",");
        ImportStep<String[], Integer> count = a -> a.length;
        ImportStep<Integer, Boolean> isMany = i -> i > 2;

        var pipeline = split.andThen(count).andThen(isMany);

        assertThat(pipeline.execute("a,b,c")).isTrue();
        assertThat(pipeline.execute("a,b")).isFalse();
    }
}
