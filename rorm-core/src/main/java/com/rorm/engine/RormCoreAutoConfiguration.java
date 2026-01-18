package com.rorm.engine;

import com.rorm.engine.handler.HandlerRegistry;
import org.jooq.DSLContext;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class RormCoreAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    HandlerRegistry handlerRegistry() {
        return HandlerRegistry.builder()
            .withBuiltIns()
            .build();
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
}
