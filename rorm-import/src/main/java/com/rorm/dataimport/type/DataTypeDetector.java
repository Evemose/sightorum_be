package com.rorm.dataimport.type;

import com.rorm.metamodel.DataType;

import java.util.*;

/**
 * Detects DataType from sample values using a parser-based approach.
 * Each type parser attempts to parse values, and the first successful match wins.
 */
public class DataTypeDetector {

    private static final int ENUM_MAX_DISTINCT_VALUES = 20;
    private static final int ENUM_MIN_REPETITIONS = 3;

    /**
     * Detects DataType from a collection of sample values.
     * Applies coercion rules by trying parsers in priority order.
     *
     * @param samples      Collection of object samples from the column (may include native types)
     * @param coercionStrategy Strategy for handling null/empty values
     * @return Detected DataType (defaults to StringType if no other type matches)
     */
    @SuppressWarnings("NullableProblems")
    public DataType detectType(Collection<?> samples, InvalidValueCoercionStrategy coercionStrategy) {
        // First, check if all samples are already of a native type
        var nativeType = detectNativeType(samples);
        if (nativeType != null) {
            return nativeType;
        }

        // Convert to strings for parsing-based detection
        var stringSamples = samples.stream()
            .filter(Objects::nonNull)
            .map(Object::toString)
            .map(coercionStrategy::processForDetection)
            .filter(Objects::nonNull)
            .toList();

        if (stringSamples.isEmpty()) {
            return new DataType.StringType();
        }

        var parsers = createParsers(stringSamples);
        for (var parser : parsers) {
            if (allValuesParse(stringSamples, parser)) {
                return parser.getDataType();
            }
        }
        return new DataType.StringType();
    }

    /**
     * Detects if all samples are of a native type (Boolean, Number, List).
     * Returns the DataType if consistent, null otherwise.
     */
    private @org.jspecify.annotations.Nullable DataType detectNativeType(Collection<?> samples) {
        var nonNullSamples = samples.stream()
            .filter(Objects::nonNull)
            .toList();

        if (nonNullSamples.isEmpty()) {
            return null;
        }

        // Check if all are Boolean
        if (nonNullSamples.stream().allMatch(Boolean.class::isInstance)) {
            return new DataType.BooleanType();
        }

        // Check if all are Numbers
        if (nonNullSamples.stream().allMatch(Number.class::isInstance)) {
            // Determine if integer or floating point
            var hasFloatingPoint = nonNullSamples.stream()
                .anyMatch(obj -> obj instanceof Float || obj instanceof Double);

            if (hasFloatingPoint) {
                return new DataType.NumericType(19, 6); // Default precision for floating point
            } else {
                return new DataType.NumericType(19, 0); // Integer
            }
        }

        // Check if all are Lists (collection type)
        if (nonNullSamples.stream().allMatch(List.class::isInstance)) {
            // Try to determine element type from list contents
            var allElements = nonNullSamples.stream()
                .filter(List.class::isInstance)
                .flatMap(obj -> ((List<?>) obj).stream())
                .toList();

            if (!allElements.isEmpty()) {
                var elementType = detectNativeType(allElements);
                if (elementType != null) {
                    return new DataType.ListType(elementType);
                }
            }
            // Default to list of strings
            return new DataType.ListType(new DataType.StringType());
        }

        return null; // Mixed types or all strings, use string-based detection
    }

    private List<TypeParser> createParsers(List<String> samples) {
        var parsers = new ArrayList<TypeParser>();

        parsers.add(new TypeParser.BooleanParser());
        parsers.add(new TypeParser.NumericParser());
        parsers.add(new TypeParser.DateTimeParser());
        parsers.add(new TypeParser.DateParser());
        parsers.add(new TypeParser.TimeParser());
        parsers.add(new TypeParser.DayOfWeekParser());
        parsers.add(new TypeParser.TimezoneParser());

        var distinctValues = samples.stream().distinct().toList();
        if (distinctValues.size() <= ENUM_MAX_DISTINCT_VALUES &&
            samples.size() >= distinctValues.size() * ENUM_MIN_REPETITIONS) {
            parsers.add(new TypeParser.EnumParser(distinctValues));
        }

        parsers.add(new TypeParser.StringParser());

        parsers.sort(Comparator.comparingInt(TypeParser::getPriority));

        return parsers;
    }

    private boolean allValuesParse(List<String> values, TypeParser parser) {
        for (var value : values) {
            try {
                if (!parser.canParse(value)) {
                    return false;
                }
            } catch (Exception _) {
                return false;
            }
        }
        return true;
    }
}
