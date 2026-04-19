package com.rorm.engine.handler;

import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.impl.DSL;

/**
 * Utilities for handling boolean Field conversions in jOOQ queries.
 * Handles the common cross-cutting issue where boolean literals (true/false)
 * are passed as filter values and need to be converted to proper Condition objects.
 */
public final class BooleanFieldUtils {

    private BooleanFieldUtils() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Converts a Field to a Condition, handling boolean literals.
     * When a boolean literal like DSL.inline(true) is encountered, it's converted
     * to DSL.trueCondition() or DSL.falseCondition() as appropriate.
     *
     * @param field the field to convert
     * @return a Condition
     * @throws ClassCastException if the field cannot be converted to a Condition
     */
    public static Condition asCondition(Field<?> field) {
        if (field instanceof Condition c) {
            return c;
        }
        // Handle boolean literals - jOOQ's DSL.inline(true) or DSL.inline(false)
        var className = field.getClass().getName();
        if (className.contains("Val") || className.contains("Inline")) {
            var str = field.toString();
            if (str.equalsIgnoreCase("true") || str.equals("1")) {
                return DSL.trueCondition();
            } else if (str.equalsIgnoreCase("false") || str.equals("0")) {
                return DSL.falseCondition();
            }
        }
        throw new ClassCastException("Cannot convert " + field.getClass() + " to Condition");
    }

    /**
     * Converts a Field to a Field&lt;Boolean&gt;, handling boolean literals.
     * This is useful for CASE WHEN conditions where a Field&lt;Boolean&gt; is expected.
     *
     * @param field the field to convert
     * @return a Field&lt;Boolean&gt;
     */
    @SuppressWarnings("unchecked")
    public static Field<Boolean> asBooleanField(Field<?> field) {
        // Handle boolean literals - jOOQ's DSL.inline(true) or DSL.inline(false)
        var className = field.getClass().getName();
        if (className.contains("Val") || className.contains("Inline")) {
            var str = field.toString();
            if (str.equalsIgnoreCase("true") || str.equals("1")) {
                return DSL.inline(true);
            } else if (str.equalsIgnoreCase("false") || str.equals("0")) {
                return DSL.inline(false);
            }
        }
        return (Field<Boolean>) field;
    }
}
