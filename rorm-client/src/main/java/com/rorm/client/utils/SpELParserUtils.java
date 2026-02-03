package com.rorm.client.utils;

import lombok.Getter;
import org.springframework.context.ApplicationContext;
import org.springframework.context.expression.BeanFactoryResolver;
import org.springframework.context.expression.MapAccessor;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.support.DefaultConversionService;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.expression.spel.support.StandardTypeConverter;
import org.springframework.expression.spel.support.StandardTypeLocator;
import org.springframework.stereotype.Component;

/**
 * Configuration for Spring Expression Language (SpEL) support in saga compensation.
 * Provides an expression parser and evaluation context factory for evaluating
 * compensation formulas with bean references.
 */
@Component
class SpELParserUtils {

    @Getter
    private final ExpressionParser expressionParser = new SpelExpressionParser();
    private final ApplicationContext applicationContext;
    private final ConversionService conversionService;

    public SpELParserUtils(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
        this.conversionService = DefaultConversionService.getSharedInstance();
    }

    /**
     * Create a new evaluation context with the given root object.
     * The context includes:
     * - Bean resolver for @beanName references
     * - Type locator for class references
     * - Type converter for automatic conversions
     *
     * @param rootObject the root object for the evaluation context
     * @return configured StandardEvaluationContext
     */
    public StandardEvaluationContext createEvaluationContext(Object rootObject) {
        var context = new StandardEvaluationContext(rootObject);
        context.addPropertyAccessor(new MapAccessor(false));
        context.setBeanResolver(new BeanFactoryResolver(applicationContext));
        context.setTypeLocator(new StandardTypeLocator());
        context.setTypeConverter(new StandardTypeConverter(conversionService));
        return context;
    }
}
