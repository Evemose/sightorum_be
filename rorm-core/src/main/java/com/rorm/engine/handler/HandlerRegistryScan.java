package com.rorm.engine.handler;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

@Configuration
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
public class HandlerRegistryScan {
}
