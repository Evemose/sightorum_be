package com.rorm.ml;

import org.springframework.boot.autoconfigure.condition.NoneNestedConditions;
import org.springframework.context.annotation.ConfigurationCondition.ConfigurationPhase;
import org.springframework.context.annotation.Conditional;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Activates a bean/configuration only when durable execution is NOT enabled —
 * logical negation of {@link ConditionalOnDurableExecution}. Defined via
 * {@link NoneNestedConditions} that wraps the positive condition, so the two
 * annotations remain a true pair: changing the durable condition automatically
 * inverts this one.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Conditional(ConditionalOnInMemoryExecution.NotDurableExecution.class)
public @interface ConditionalOnInMemoryExecution {

    class NotDurableExecution extends NoneNestedConditions {

        NotDurableExecution() {
            super(ConfigurationPhase.REGISTER_BEAN);
        }

        @ConditionalOnDurableExecution
        static class OnDurable {
        }
    }
}
