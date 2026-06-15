package com.rorm.query;

import com.rorm.engine.handler.HandlerRegistry;
import com.rorm.testutil.TestHandlerRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the contract that every standard function constant names a handler that is actually registered, so a new
 * enum constant added without its handler (or with a mismatched identifier) fails here rather than at query time.
 */
@DisplayName("Standard function identifiers resolve to registered handlers")
class StandardFunctionRegistryTest {

    private final HandlerRegistry registry = TestHandlerRegistry.createWithAllBuiltIns();

    @ParameterizedTest
    @EnumSource(StandardAggregation.class)
    @DisplayName("every standard aggregation resolves to an aggregation handler")
    void aggregationsResolve(StandardAggregation aggregation) {
        assertThat(aggregation.identifier()).isNotBlank();
        assertThat(registry.getAggregation(aggregation.identifier())).isNotNull();
    }

    @ParameterizedTest
    @EnumSource(StandardWindowFunction.class)
    @DisplayName("every standard window function resolves to a window handler")
    void windowFunctionsResolve(StandardWindowFunction windowFunction) {
        assertThat(windowFunction.identifier()).isNotBlank();
        assertThat(registry.getWindowFunction(windowFunction.identifier())).isNotNull();
    }

    @Test
    @DisplayName("aggregation identifiers are distinct, so no constant aliases another's handler")
    void aggregationIdentifiersAreDistinct() {
        assertThat(Arrays.stream(StandardAggregation.values()).map(StandardAggregation::identifier).toList())
            .doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("window function identifiers are distinct, so no constant aliases another's handler")
    void windowFunctionIdentifiersAreDistinct() {
        assertThat(Arrays.stream(StandardWindowFunction.values()).map(StandardWindowFunction::identifier).toList())
            .doesNotHaveDuplicates();
    }
}
