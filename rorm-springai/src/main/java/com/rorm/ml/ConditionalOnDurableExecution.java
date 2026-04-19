package com.rorm.ml;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Activates a bean/configuration only when durable execution (Restate-backed)
 * is enabled via {@code rorm.ml.durable-execution=true}. Paired with
 * {@link ConditionalOnInMemoryExecution} which is its logical negation.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ConditionalOnProperty(name = "rorm.ml.durable-execution", havingValue = "true")
public @interface ConditionalOnDurableExecution {
}
