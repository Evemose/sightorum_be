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
     * @param samples      Collection of string samples from the column
     * @param nullStrategy Strategy for handling null/empty values
     * @return Detected DataType (defaults to StringType if no other type matches)
     */
    @SuppressWarnings("NullableProblems")
    public DataType detectType(Collection<String> samples, NullCoalescingStrategy nullStrategy) {
        var processedSamples = samples.stream()
            .map(nullStrategy::process)
            .filter(Objects::nonNull)
            .toList();
        if (processedSamples.isEmpty()) {
            return new DataType.StringType();
        }
        var parsers = createParsers(processedSamples);
        for (var parser : parsers) {
            if (allValuesParse(processedSamples, parser)) {
                return parser.getDataType();
            }
        }
        return new DataType.StringType();
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
