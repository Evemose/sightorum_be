package com.rorm.client.utils;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/// Annotation to specify the schema context for a class or method.
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface WithSchema {
    /// A SpEL expression that resolves to the schema name.
    ///
    /// Available variables:
    /// - `this`: the current object instance
    /// - method parameters (by name)
    String value();
}
