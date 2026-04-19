package com.rorm.dataimport.type;

import com.rorm.metamodel.DataType;
import com.rorm.metamodel.DataType.CategorcialType;

import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Parser for detecting and validating a specific DataType from string values.
 * Each parser attempts to parse values according to its type's rules.
 */
public interface TypeParser {

    /**
     * Utility method to parse a string value to appropriate Java type based on DataType.
     * Delegates to the appropriate parser implementation.
     */
    static Object parseValue(String value, DataType dataType) {
        return switch (dataType) {
            case DataType.BooleanType _ -> new BooleanParser().parse(value);
            case DataType.NumericType numeric -> {
                var parser = new NumericParser();
                // Set the precision/scale to match the provided dataType
                parser.maxPrecision = numeric.precision();
                parser.maxScale = numeric.scale();
                yield parser.parse(value);
            }
            case DataType.DateTimeType _ -> new DateTimeParser().parse(value);
            case DataType.DateType _ -> new DateParser().parse(value);
            case DataType.TimeType _ -> new TimeParser().parse(value);
            case DataType.DayOfWeekType _ -> new DayOfWeekParser().parse(value);
            case DataType.TimezoneType _ -> new TimezoneParser().parse(value);
            case DataType.CategorcialType enm -> new EnumParser(List.of(enm.values())).parse(value);
            case DataType.StringType _ -> new StringParser().parse(value);
            case DataType.IntervalType _ -> value; // Intervals stored as strings
            case DataType.ListType _ -> value; // Collections handled separately
        };
    }

    /**
     * The DataType this parser detects.
     */
    DataType getDataType();

    /**
     * Attempts to parse the value according to this type's rules.
     * Returns true if the value matches this type, false otherwise.
     * Throws exception if parsing fails (value doesn't match type).
     */
    boolean canParse(String value) throws Exception;

    /**
     * Parses a string value to the appropriate Java type for database insertion.
     * Returns the parsed value (Long, BigDecimal, LocalDate, Boolean, etc.)
     */
    Object parse(String value);

    /**
     * Priority for type detection. Lower number = higher priority.
     * This ensures boolean is checked before numeric, etc.
     */
    int getPriority();

    class BooleanParser implements TypeParser {
        private static final Pattern PATTERN = Pattern.compile("^(true|false|t|f|yes|no|y|n)$", Pattern.CASE_INSENSITIVE);

        @Override
        public DataType getDataType() {
            return new DataType.BooleanType();
        }

        @Override
        public boolean canParse(String value) {
            return PATTERN.matcher(value).matches();
        }

        @Override
        public Object parse(String value) {
            return switch (value.toLowerCase()) {
                case "true", "t", "yes", "y" -> true;
                case "false", "f", "no", "n" -> false;
                default -> throw new IllegalArgumentException("Invalid boolean value: " + value);
            };
        }

        @Override
        public int getPriority() {
            return 1; // Highest priority
        }
    }

    class NumericParser implements TypeParser {
        private static final Pattern INTEGER_PATTERN = Pattern.compile("^-?\\d+$");
        private static final Pattern DECIMAL_PATTERN = Pattern.compile("^-?\\d+\\.\\d+$");

        private int maxPrecision = 0;
        private int maxScale = 0;

        @Override
        public boolean canParse(String value) {
            if (INTEGER_PATTERN.matcher(value).matches()) {
                maxPrecision = Math.max(maxPrecision, value.replace("-", "").length());
                return true;
            } else if (DECIMAL_PATTERN.matcher(value).matches()) {
                var parts = value.replace("-", "").split("\\.");
                maxPrecision = Math.max(maxPrecision, parts[0].length() + parts[1].length());
                maxScale = Math.max(maxScale, parts[1].length());
                return true;
            }
            return false;
        }

        @Override
        public Object parse(String value) {
            var dataType = (DataType.NumericType) getDataType();
            if (dataType.scale() > 0) {
                var doubleValue = Double.parseDouble(value);
                if (Double.isInfinite(doubleValue) || Double.isNaN(doubleValue)) {
                    return new BigDecimal(value);
                } else {
                    return doubleValue;
                }
            }
            var longValue = Long.parseLong(value);
            if (dataType.precision() <= 4) {
                return (short) longValue;
            } else if (dataType.precision() <= 9) {
                return (int) longValue;
            } else {
                return longValue;
            }
        }

        @Override
        public DataType getDataType() {
            // Ensure precision is at least 19 and always greater than scale
            // Also cap scale at a reasonable value to avoid overflow issues
            var effectiveScale = Math.min(maxScale, 6);
            var effectivePrecision = Math.max(Math.max(maxPrecision, 19), effectiveScale + 1);
            return new DataType.NumericType(effectivePrecision, effectiveScale);
        }

        @Override
        public int getPriority() {
            return 2;
        }
    }

    class DateTimeParser implements TypeParser {
        private static final List<DateTimeFormatter> FORMATTERS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ISO_OFFSET_DATE_TIME,
            DateTimeFormatter.ISO_ZONED_DATE_TIME,
            DateTimeFormatter.ISO_INSTANT,
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss")
        );

        @Override
        public DataType getDataType() {
            return new DataType.DateTimeType();
        }

        @Override
        public boolean canParse(String value) {
            return FORMATTERS.stream().anyMatch(formatter -> {
                try {
                    formatter.parse(value);
                    return true;
                } catch (DateTimeParseException _) {
                    return false;
                }
            });
        }

        @Override
        public Object parse(String value) {
            for (var formatter : FORMATTERS) {
                try {
                    var temporal = formatter.parse(value);
                    if (temporal.isSupported(java.time.temporal.ChronoField.INSTANT_SECONDS)) {
                        return OffsetDateTime.from(temporal);
                    }
                } catch (DateTimeParseException _) {
                    // Try next formatter
                }
            }
            // Try parsing as LocalDateTime and convert to OffsetDateTime with system zone
            var localFormatters = List.of(
                DateTimeFormatter.ISO_LOCAL_DATE_TIME,
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
                DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss")
            );
            for (var formatter : localFormatters) {
                try {
                    var localDateTime = LocalDateTime.parse(value, formatter);
                    return localDateTime.atZone(ZoneId.systemDefault()).toOffsetDateTime();
                } catch (DateTimeParseException _) {
                    // Try next formatter
                }
            }
            throw new IllegalArgumentException("Invalid datetime format: " + value);
        }

        @Override
        public int getPriority() {
            return 3;
        }
    }

    class DateParser implements TypeParser {
        private static final List<DateTimeFormatter> FORMATTERS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("yyyy/MM/dd"),
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("MM/dd/yyyy"),
            DateTimeFormatter.ofPattern("dd-MM-yyyy"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd")
        );

        @Override
        public DataType getDataType() {
            return new DataType.DateType();
        }

        @Override
        public boolean canParse(String value) {
            return FORMATTERS.stream().anyMatch(formatter -> {
                try {
                    LocalDate.parse(value, formatter);
                    return true;
                } catch (DateTimeParseException _) {
                    return false;
                }
            });
        }

        @Override
        public Object parse(String value) {
            for (var formatter : FORMATTERS) {
                try {
                    return LocalDate.parse(value, formatter);
                } catch (DateTimeParseException _) {
                    // Try next formatter
                }
            }
            throw new IllegalArgumentException("Invalid date format: " + value);
        }

        @Override
        public int getPriority() {
            return 4;
        }
    }

    class TimeParser implements TypeParser {
        private static final List<DateTimeFormatter> FORMATTERS = List.of(
            DateTimeFormatter.ISO_LOCAL_TIME,
            DateTimeFormatter.ofPattern("HH:mm:ss"),
            DateTimeFormatter.ofPattern("HH:mm")
        );

        @Override
        public DataType getDataType() {
            return new DataType.TimeType();
        }

        @Override
        public boolean canParse(String value) {
            return FORMATTERS.stream().anyMatch(formatter -> {
                try {
                    LocalTime.parse(value, formatter);
                    return true;
                } catch (DateTimeParseException _) {
                    return false;
                }
            });
        }

        @Override
        public Object parse(String value) {
            for (var formatter : FORMATTERS) {
                try {
                    return LocalTime.parse(value, formatter);
                } catch (DateTimeParseException _) {
                    // Try next formatter
                }
            }
            throw new IllegalArgumentException("Invalid time format: " + value);
        }

        @Override
        public int getPriority() {
            return 5;
        }
    }

    class DayOfWeekParser implements TypeParser {
        @Override
        public DataType getDataType() {
            return new DataType.DayOfWeekType();
        }

        @Override
        public boolean canParse(String value) {
            try {
                DayOfWeek.valueOf(value.toUpperCase());
                return true;
            } catch (IllegalArgumentException _) {
                return false;
            }
        }

        @Override
        public Object parse(String value) {
            return DayOfWeek.valueOf(value.toUpperCase());
        }

        @Override
        public int getPriority() {
            return 6;
        }
    }

    class TimezoneParser implements TypeParser {
        @Override
        public DataType getDataType() {
            return new DataType.TimezoneType();
        }

        @Override
        public boolean canParse(String value) {
            try {
                ZoneId.of(value);
                return true;
            } catch (Exception _) {
                try {
                    ZoneOffset.of(value);
                    return true;
                } catch (Exception _) {
                    return false;
                }
            }
        }

        @Override
        public Object parse(String value) {
            // Try parsing as ZoneId or ZoneOffset to validate, return as string
            try {
                ZoneId.of(value);
                return value;
            } catch (Exception _) {
                try {
                    ZoneOffset.of(value);
                    return value;
                } catch (Exception _) {
                    throw new IllegalArgumentException("Invalid timezone: " + value);
                }
            }
        }

        @Override
        public int getPriority() {
            return 7;
        }
    }

    class EnumParser implements TypeParser {
        private final List<String> distinctValues;

        public EnumParser(List<String> distinctValues) {
            this.distinctValues = distinctValues;
        }

        @Override
        public DataType getDataType() {
            return new CategorcialType(distinctValues.toArray(String[]::new));
        }

        @Override
        public boolean canParse(String value) {
            return distinctValues.contains(value);
        }

        @Override
        public Object parse(String value) {
            return value; // Store as string
        }

        @Override
        public int getPriority() {
            return 8;
        }
    }

    class StringParser implements TypeParser {
        @Override
        public DataType getDataType() {
            return new DataType.StringType();
        }

        @Override
        public boolean canParse(String value) {
            return true; // Always succeeds (fallback)
        }

        @Override
        public Object parse(String value) {
            return value;
        }

        @Override
        public int getPriority() {
            return Integer.MAX_VALUE; // Lowest priority (fallback)
        }
    }
}
