package com.rorm.dataimport.type;

import org.jspecify.annotations.Nullable;

/**
 * Strategy for handling null/empty values during type detection and parsing.
 */
public sealed interface NullCoalescingStrategy {

    /**
     * Processes a potentially null or empty value according to the strategy.
     * Returns null if the value should be skipped, or a value to be parsed.
     */
    @Nullable
    String process(@Nullable String value);

    /**
     * Skip null/empty values - don't include them in type inference.
     * This is the default and most robust strategy.
     */
    record SkipNulls() implements NullCoalescingStrategy {
        public static final SkipNulls INSTANCE = new SkipNulls();

        @Override
        public @Nullable String process(@Nullable String value) {
            if (value == null || value.isEmpty()) {
                return null; // Signal to skip this value
            }
            return value.trim();
        }
    }

    /**
     * Treat null/empty values as empty strings.
     * This will tend to result in StringType for columns with any nulls.
     */
    record TreatAsString() implements NullCoalescingStrategy {
        public static final TreatAsString INSTANCE = new TreatAsString();

        @Override
        public @Nullable String process(@Nullable String value) {
            if (value == null || value.isEmpty()) {
                return "";
            }
            return value.trim();
        }
    }

    /**
     * Replace null/empty values with type-appropriate defaults.
     * This is used during import, not during type detection.
     */
    record UseDefaultValues(DefaultValueStrategy defaultValueStrategy) implements NullCoalescingStrategy {
        @Override
        public @Nullable String process(@Nullable String value) {
            // This strategy is not used during type detection
            // It's used later during actual data import
            if (value == null || value.isEmpty()) {
                return null;
            }
            return value.trim();
        }
    }
}
