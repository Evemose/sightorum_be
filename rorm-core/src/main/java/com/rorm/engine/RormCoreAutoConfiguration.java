package com.rorm.engine;

import com.rorm.engine.handler.*;
import org.jooq.DSLContext;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

import java.util.List;
import java.util.stream.Collectors;

@AutoConfiguration
@ComponentScan(
    basePackages = "com.rorm.engine.handler",
    includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = {
        FunctionHandler.class,
        AggregationHandler.class,
        WindowFunctionHandler.class,
        UnaryOperatorHandler.class,
        BinaryOperatorHandler.class,
        TernaryOperatorHandler.class
    })
)
public class RormCoreAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    HandlerRegistry handlerRegistry(
        List<FunctionHandler> functionHandlers,
        List<AggregationHandler> aggregationHandlers,
        List<WindowFunctionHandler> windowFunctionHandlers,
        List<UnaryOperatorHandler> unaryOperatorHandlers,
        List<BinaryOperatorHandler> binaryOperatorHandlers,
        List<TernaryOperatorHandler> ternaryOperatorHandlers
    ) {
        return new HandlerRegistry(
            functionHandlers.stream().collect(Collectors.toMap(FunctionHandler::name, h -> h)),
            aggregationHandlers.stream().collect(Collectors.toMap(AggregationHandler::name, h -> h)),
            windowFunctionHandlers.stream().collect(Collectors.toMap(WindowFunctionHandler::name, h -> h)),
            unaryOperatorHandlers.stream().collect(Collectors.toMap(UnaryOperatorHandler::name, h -> h)),
            binaryOperatorHandlers.stream().collect(Collectors.toMap(BinaryOperatorHandler::name, h -> h)),
            ternaryOperatorHandlers.stream().collect(Collectors.toMap(TernaryOperatorHandler::name, h -> h))
        );
    }

    @Bean
    @ConditionalOnMissingBean
    ExpressionTransformer expressionTransformer(HandlerRegistry handlerRegistry) {
        return new ExpressionTransformer(handlerRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    ExpressionTypeResolver expressionTypeResolver(HandlerRegistry handlerRegistry) {
        return new ExpressionTypeResolver(handlerRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    JoinCollector joinCollector(ExpressionTransformer expressionTransformer) {
        return new JoinCollector(expressionTransformer);
    }

    @Bean
    @ConditionalOnMissingBean
    SubqueryTransformer subqueryTransformer(
        ExpressionTransformer expressionTransformer,
        JoinCollector joinCollector
    ) {
        var transformer = new SubqueryTransformer(expressionTransformer, joinCollector);
        expressionTransformer.setSubqueryTransformer(transformer);
        return transformer;
    }

    @Bean
    @ConditionalOnMissingBean
    QueryTransformer queryTransformer(
        DSLContext dsl,
        ExpressionTransformer expressionTransformer,
        JoinCollector joinCollector
    ) {
        return new QueryTransformer(dsl, expressionTransformer, joinCollector);
    }

    @Bean
    @ConditionalOnMissingBean
    com.rorm.fetcher.Fetcher fetcher(DSLContext dsl, QueryTransformer queryTransformer) {
        return new com.rorm.fetcher.JooqFetcher(dsl, queryTransformer);
    }
}
