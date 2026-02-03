package com.rorm.client.utils;

import com.rorm.fetcher.Fetcher;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.HashMap;

import static org.springframework.core.Ordered.HIGHEST_PRECEDENCE;

@Aspect
@Component
@Order(HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class WithSchemaAspect {

    private final Fetcher fetcher;
    private final SpELEvaluator spelEvaluator;

    @Around("@annotation(com.rorm.client.utils.WithSchema) || @within(com.rorm.client.utils.WithSchema)")
    public Object aroundWithSchemaAnnotation(ProceedingJoinPoint joinPoint) throws Throwable {
        var schema = extractSchema(joinPoint);
        return fetcher.withSchema(schema, joinPoint::proceed);
    }

    private String extractSchema(ProceedingJoinPoint joinPoint) {
        var annotation = findAnnotation(joinPoint);
        var expression = annotation.value();
        var context = createEvaluationContext(joinPoint);
        return spelEvaluator.evaluate(expression, String.class, context);
    }

    private WithSchema findAnnotation(ProceedingJoinPoint joinPoint) {
        var method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        var annotation = method.getAnnotation(WithSchema.class);
        if (annotation == null) {
            annotation = AopUtils.getTargetClass(joinPoint.getTarget()).getAnnotation(WithSchema.class);
        }
        return annotation;
    }

    private Object createEvaluationContext(ProceedingJoinPoint joinPoint) {
        var target = joinPoint.getThis();
        var args = joinPoint.getArgs();
        var parameterNames = ((MethodSignature) joinPoint.getSignature()).getParameterNames();
        var map = new HashMap<String, Object>();
        map.put("this", target);
        for (int i = 0; i < parameterNames.length; i++) {
            map.put(parameterNames[i], args[i]);
        }
        return map;
    }

}
