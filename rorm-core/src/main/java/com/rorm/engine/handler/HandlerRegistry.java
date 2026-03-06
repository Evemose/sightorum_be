package com.rorm.engine.handler;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Registry for expression handlers.
 * <p>
 * Provides lookup methods for finding handlers by name. Handlers are registered
 * at construction time and are immutable thereafter.
 * <p>
 * Use {@link HandlerRegistryBuilder} to create instances with custom handlers.
 */
@Component
public class HandlerRegistry {

    private final Map<String, FunctionHandler> functions;
    private final Map<String, AggregationHandler> aggregations;
    private final Map<String, WindowFunctionHandler> windowFunctions;
    private final Map<String, UnaryOperatorHandler> unaryOperators;
    private final Map<String, BinaryOperatorHandler> binaryOperators;
    private final Map<String, TernaryOperatorHandler> ternaryOperators;

    @Autowired
    public HandlerRegistry(
        List<FunctionHandler> functionHandlers,
        List<AggregationHandler> aggregationHandlers,
        List<WindowFunctionHandler> windowFunctionHandlers,
        List<UnaryOperatorHandler> unaryOperatorHandlers,
        List<BinaryOperatorHandler> binaryOperatorHandlers,
        List<TernaryOperatorHandler> ternaryOperatorHandlers
    ) {
        this.functions = functionHandlers.stream().collect(Collectors.toMap(h -> normalize(h.name()), h -> h));
        this.aggregations = aggregationHandlers.stream().collect(Collectors.toMap(h -> normalize(h.name()), h -> h));
        this.windowFunctions = windowFunctionHandlers.stream().collect(Collectors.toMap(h -> normalize(h.name()), h -> h));
        this.unaryOperators = unaryOperatorHandlers.stream().collect(Collectors.toMap(h -> normalize(h.name()), h -> h));
        this.binaryOperators = binaryOperatorHandlers.stream().collect(Collectors.toMap(h -> normalize(h.name()), h -> h));
        this.ternaryOperators = ternaryOperatorHandlers.stream().collect(Collectors.toMap(h -> normalize(h.name()), h -> h));
    }

    private static String normalize(String name) {
        return name.toUpperCase();
    }

    private HandlerRegistry(
        Map<String, FunctionHandler> functions,
        Map<String, AggregationHandler> aggregations,
        Map<String, WindowFunctionHandler> windowFunctions,
        Map<String, UnaryOperatorHandler> unaryOperators,
        Map<String, BinaryOperatorHandler> binaryOperators,
        Map<String, TernaryOperatorHandler> ternaryOperators
    ) {
        this.functions = Map.copyOf(functions);
        this.aggregations = Map.copyOf(aggregations);
        this.windowFunctions = Map.copyOf(windowFunctions);
        this.unaryOperators = Map.copyOf(unaryOperators);
        this.binaryOperators = Map.copyOf(binaryOperators);
        this.ternaryOperators = Map.copyOf(ternaryOperators);
    }

    /**
     * Creates a new builder for constructing a HandlerRegistry.
     *
     * @return a new builder instance
     */
    public static HandlerRegistryBuilder builder() {
        return new HandlerRegistryBuilder();
    }

    /**
     * Gets a function handler by name, throwing if not found.
     *
     * @param name the function name
     * @return the handler
     * @throws IllegalArgumentException if no handler found
     */
    public FunctionHandler getFunction(String name) {
        return findFunction(name).orElseThrow(() ->
            new IllegalArgumentException("Unknown function: " + name));
    }

    /**
     * Looks up a function handler by name.
     *
     * @param name the function name (case-insensitive)
     * @return the handler, or empty if not found
     */
    public Optional<FunctionHandler> findFunction(String name) {
        return Optional.ofNullable(functions.get(normalize(name)));
    }

    /**
     * Gets an aggregation handler by name, throwing if not found.
     *
     * @param name the aggregation name
     * @return the handler
     * @throws IllegalArgumentException if no handler found
     */
    public AggregationHandler getAggregation(String name) {
        return findAggregation(name).orElseThrow(() ->
            new IllegalArgumentException("Unknown aggregation: " + name));
    }

    /**
     * Looks up an aggregation handler by name.
     *
     * @param name the aggregation name (case-insensitive)
     * @return the handler, or empty if not found
     */
    public Optional<AggregationHandler> findAggregation(String name) {
        return Optional.ofNullable(aggregations.get(normalize(name)));
    }

    /**
     * Gets a window function handler by name, throwing if not found.
     *
     * @param name the window function name
     * @return the handler
     * @throws IllegalArgumentException if no handler found
     */
    public WindowFunctionHandler getWindowFunction(String name) {
        return findWindowFunction(name).orElseThrow(() ->
            new IllegalArgumentException("Unknown window function: " + name));
    }

    /**
     * Looks up a window function handler by name.
     *
     * @param name the window function name (case-insensitive)
     * @return the handler, or empty if not found
     */
    public Optional<WindowFunctionHandler> findWindowFunction(String name) {
        return Optional.ofNullable(windowFunctions.get(normalize(name)));
    }

    /**
     * Gets a unary operator handler by name, throwing if not found.
     *
     * @param name the operator name
     * @return the handler
     * @throws IllegalArgumentException if no handler found
     */
    public UnaryOperatorHandler getUnaryOperator(String name) {
        return findUnaryOperator(name).orElseThrow(() ->
            new IllegalArgumentException("Unknown unary operator: " + name));
    }

    /**
     * Looks up a unary operator handler by name.
     *
     * @param name the operator name (case-insensitive)
     * @return the handler, or empty if not found
     */
    public Optional<UnaryOperatorHandler> findUnaryOperator(String name) {
        return Optional.ofNullable(unaryOperators.get(normalize(name)));
    }

    /**
     * Gets a binary operator handler by name, throwing if not found.
     *
     * @param name the operator name
     * @return the handler
     * @throws IllegalArgumentException if no handler found
     */
    public BinaryOperatorHandler getBinaryOperator(String name) {
        return findBinaryOperator(name).orElseThrow(() ->
            new IllegalArgumentException("Unknown binary operator: " + name));
    }

    /**
     * Looks up a binary operator handler by name.
     *
     * @param name the operator name (case-insensitive)
     * @return the handler, or empty if not found
     */
    public Optional<BinaryOperatorHandler> findBinaryOperator(String name) {
        return Optional.ofNullable(binaryOperators.get(normalize(name)));
    }

    /**
     * Gets a ternary operator handler by name, throwing if not found.
     *
     * @param name the operator name
     * @return the handler
     * @throws IllegalArgumentException if no handler found
     */
    public TernaryOperatorHandler getTernaryOperator(String name) {
        return findTernaryOperator(name).orElseThrow(() ->
            new IllegalArgumentException("Unknown ternary operator: " + name));
    }

    /**
     * Looks up a ternary operator handler by name.
     *
     * @param name the operator name (case-insensitive)
     * @return the handler, or empty if not found
     */
    public Optional<TernaryOperatorHandler> findTernaryOperator(String name) {
        return Optional.ofNullable(ternaryOperators.get(normalize(name)));
    }

    /**
     * Builder for creating HandlerRegistry instances.
     */
    public static class HandlerRegistryBuilder {
        private final Map<String, FunctionHandler> functions = new HashMap<>();
        private final Map<String, AggregationHandler> aggregations = new HashMap<>();
        private final Map<String, WindowFunctionHandler> windowFunctions = new HashMap<>();
        private final Map<String, UnaryOperatorHandler> unaryOperators = new HashMap<>();
        private final Map<String, BinaryOperatorHandler> binaryOperators = new HashMap<>();
        private final Map<String, TernaryOperatorHandler> ternaryOperators = new HashMap<>();


        /**
         * Registers multiple function handlers.
         *
         * @param handlers the handlers to register
         * @return this builder
         */
        public HandlerRegistryBuilder functions(List<? extends FunctionHandler> handlers) {
            handlers.forEach(this::function);
            return this;
        }

        /**
         * Registers a function handler.
         *
         * @param handler the handler to register
         * @return this builder
         */
        public HandlerRegistryBuilder function(FunctionHandler handler) {
            functions.put(normalize(handler.name()), handler);
            return this;
        }

        private static String normalize(String name) {
            return name.toUpperCase();
        }

        /**
         * Registers multiple aggregation handlers.
         *
         * @param handlers the handlers to register
         * @return this builder
         */
        public HandlerRegistryBuilder aggregations(List<? extends AggregationHandler> handlers) {
            handlers.forEach(this::aggregation);
            return this;
        }

        /**
         * Registers an aggregation handler.
         *
         * @param handler the handler to register
         * @return this builder
         */
        public HandlerRegistryBuilder aggregation(AggregationHandler handler) {
            aggregations.put(normalize(handler.name()), handler);
            return this;
        }

        /**
         * Registers multiple window function handlers.
         *
         * @param handlers the handlers to register
         * @return this builder
         */
        public HandlerRegistryBuilder windowFunctions(List<? extends WindowFunctionHandler> handlers) {
            handlers.forEach(this::windowFunction);
            return this;
        }

        /**
         * Registers a window function handler.
         *
         * @param handler the handler to register
         * @return this builder
         */
        public HandlerRegistryBuilder windowFunction(WindowFunctionHandler handler) {
            windowFunctions.put(normalize(handler.name()), handler);
            return this;
        }

        /**
         * Registers multiple unary operator handlers.
         *
         * @param handlers the handlers to register
         * @return this builder
         */
        public HandlerRegistryBuilder unaryOperators(List<? extends UnaryOperatorHandler> handlers) {
            handlers.forEach(this::unaryOperator);
            return this;
        }

        /**
         * Registers a unary operator handler.
         *
         * @param handler the handler to register
         * @return this builder
         */
        public HandlerRegistryBuilder unaryOperator(UnaryOperatorHandler handler) {
            unaryOperators.put(normalize(handler.name()), handler);
            return this;
        }

        /**
         * Registers multiple binary operator handlers.
         *
         * @param handlers the handlers to register
         * @return this builder
         */
        public HandlerRegistryBuilder binaryOperators(List<? extends BinaryOperatorHandler> handlers) {
            handlers.forEach(this::binaryOperator);
            return this;
        }

        /**
         * Registers a binary operator handler.
         *
         * @param handler the handler to register
         * @return this builder
         */
        public HandlerRegistryBuilder binaryOperator(BinaryOperatorHandler handler) {
            binaryOperators.put(normalize(handler.name()), handler);
            return this;
        }

        /**
         * Registers multiple ternary operator handlers.
         *
         * @param handlers the handlers to register
         * @return this builder
         */
        public HandlerRegistryBuilder ternaryOperators(List<? extends TernaryOperatorHandler> handlers) {
            handlers.forEach(this::ternaryOperator);
            return this;
        }

        /**
         * Registers a ternary operator handler.
         *
         * @param handler the handler to register
         * @return this builder
         */
        public HandlerRegistryBuilder ternaryOperator(TernaryOperatorHandler handler) {
            ternaryOperators.put(normalize(handler.name()), handler);
            return this;
        }

        /**
         * Builds the registry with all registered handlers.
         *
         * @return the constructed registry
         */
        public HandlerRegistry build() {
            return new HandlerRegistry(
                functions,
                aggregations,
                windowFunctions,
                unaryOperators,
                binaryOperators,
                ternaryOperators
            );
        }
    }
}
