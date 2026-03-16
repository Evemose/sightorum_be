package com.rorm.durable;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class as a durable job whose execution can be checkpointed and resumed.
 * <p>
 * The annotation processor generates a submitter class that:
 * <ul>
 *   <li>Copies the job's constructor dependencies (Spring-injected fields)</li>
 *   <li>Exposes a {@code submit()} method with the entry method's parameters</li>
 *   <li>Creates a new job instance on each submission with the injected deps</li>
 *   <li>Calls the entry method with only the declared params — enforcing isolation</li>
 * </ul>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface DurableJob {
}
