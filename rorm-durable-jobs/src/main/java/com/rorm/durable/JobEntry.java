package com.rorm.durable;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the entry point method of a {@link DurableJob}.
 * Parameters are serialized into the {@link JobSpec} — they must be serializable.
 * The method uses {@link StepJournal#current()} to journal non-deterministic steps.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.SOURCE)
public @interface JobEntry {
}
