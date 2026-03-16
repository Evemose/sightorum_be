package com.rorm.durable;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the entry point method of a {@link DurableJob}.
 * <p>
 * Parameters of this method form the checkpoint boundary — they are serialized
 * into the durable execution journal and are the only data available on replay.
 * Constructor-injected fields (services, datasources) are re-injected on each invocation.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.SOURCE)
public @interface JobEntry {
}
