package com.rorm.metamodel;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.List;
import java.util.stream.Stream;

public sealed interface DataType {

    Object valueOf(Object value);

    Class<?> javaType();

    record NumericType(int precision, int scale) implements DataType {

        @Override
        public Class<? extends Number> javaType() {
            if (scale == 0) {
                if (precision <= 9) {
                    return int.class;
                } else if (precision <= 18) {
                    return long.class;
                } else {
                    return BigDecimal.class;
                }
            }
            if (precision <= 7) {
                return float.class;
            } else if (precision <= 16) {
                return double.class;
            }
            return BigDecimal.class;
        }

        @Override
        public Number valueOf(Object value) {
            if (value instanceof Number number) {
                if (scale == 0) {
                    if (precision <= 9) {
                        return number.intValue();
                    } else if (precision <= 18) {
                        return number.longValue();
                    } else if (precision <= 38) {
                        return new BigDecimal(number.toString()).setScale(0, RoundingMode.HALF_UP);
                    }
                } else {
                    if (precision <= 15) {
                        return number.doubleValue();
                    } else if (precision <= 38) {
                        return new BigDecimal(number.toString()).setScale(scale, RoundingMode.HALF_UP);
                    }
                }
            }
            throw new IllegalArgumentException("Invalid numeric value: " + value);
        }
    }

    record StringType() implements DataType {
        @Override
        public String valueOf(Object value) {
            return value.toString();
        }

        @Override
        public Class<String> javaType() {
            return String.class;
        }
    }

    record BooleanType() implements DataType {
        @Override
        public Boolean valueOf(Object value) {
            if (value instanceof Boolean bool) {
                return bool;
            } else if (value instanceof String str) {
                return Boolean.parseBoolean(str);
            }
            throw new IllegalArgumentException("Invalid boolean value: " + value);
        }

        @Override
        public Class<Boolean> javaType() {
            return boolean.class;
        }
    }

    record DateType() implements DataType {
        @Override
        public LocalDate valueOf(Object value) {
            if (value instanceof LocalDate date) {
                return date;
            } else if (value instanceof String str) {
                return LocalDate.parse(str);
            }
            throw new IllegalArgumentException("Invalid date value: " + value);
        }

        @Override
        public Class<LocalDate> javaType() {
            return LocalDate.class;
        }
    }

    record TimeType() implements DataType {
        @Override
        public LocalTime valueOf(Object value) {
            if (value instanceof LocalTime time) {
                return time;
            } else if (value instanceof String str) {
                return LocalTime.parse(str);
            }
            throw new IllegalArgumentException("Invalid time value: " + value);
        }

        @Override
        public Class<LocalTime> javaType() {
            return LocalTime.class;
        }
    }

    record TimezoneType() implements DataType {
        @Override
        public ZoneId valueOf(Object value) {
            if (value instanceof ZoneId zone) {
                return zone;
            } else if (value instanceof String str) {
                return ZoneId.of(str);
            }
            throw new IllegalArgumentException("Invalid timezone value: " + value);
        }

        @Override
        public Class<ZoneId> javaType() {
            return ZoneId.class;
        }
    }

    record DateTimeType() implements DataType {
        @Override
        public ZonedDateTime valueOf(Object value) {
            if (value instanceof ZonedDateTime dateTime) {
                return dateTime;
            } else if (value instanceof String str) {
                return ZonedDateTime.parse(str);
            }
            throw new IllegalArgumentException("Invalid datetime value: " + value);
        }

        @Override
        public Class<ZonedDateTime> javaType() {
            return ZonedDateTime.class;
        }
    }

    record DayOfWeekType() implements DataType {
        @Override
        public DayOfWeek valueOf(Object value) {
            if (value instanceof DayOfWeek day) {
                return day;
            } else if (value instanceof String str) {
                return DayOfWeek.valueOf(str.toUpperCase());
            }
            throw new IllegalArgumentException("Invalid day of week value: " + value);
        }

        @Override
        public Class<DayOfWeek> javaType() {
            return DayOfWeek.class;
        }
    }

    record CategorcialType(String[] values) implements DataType {
        @Override
        public String valueOf(Object value) {
            if (value instanceof String str) {
                for (String enumValue : values) {
                    if (enumValue.equals(str)) {
                        return str;
                    }
                }
            }
            throw new IllegalArgumentException("Invalid enum value: " + value);
        }

        @Override
        public Class<String> javaType() {
            return String.class;
        }
    }

    record ListType(DataType elementType) implements DataType {
        @Override
        public List<?> valueOf(Object value) {
            if (value instanceof List<?> list) {
                return list.stream().map(elementType::valueOf).toList();
            } else if (value.getClass().isArray()) {
                var array = (Object[]) value;
                return Stream.of(array).map(elementType::valueOf).toList();
            }
            throw new IllegalArgumentException("Invalid list value: " + value);
        }

        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        public Class<List<?>> javaType() {
            return (Class) List.class;
        }
    }
}
