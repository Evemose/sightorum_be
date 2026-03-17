package com.rorm.durable;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a Spring bean as a durable job.
 * The annotation processor generates a type-safe submitter that builds a {@link JobSpec}
 * and dispatches it to the {@link DurableRuntime}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface DurableJob {
}
