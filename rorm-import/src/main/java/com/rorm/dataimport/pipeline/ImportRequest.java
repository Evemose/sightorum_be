package com.rorm.dataimport.pipeline;

import com.rorm.dataimport.attribute.DetectedAttribute;
import com.rorm.dataimport.source.ImportDataSource;
import com.rorm.dataimport.type.InvalidValueCoercionStrategy;
import com.rorm.dataimport.type.NumericCoercionStrategy;
import com.rorm.metamodel.DataType;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.With;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public record ImportRequest(
    @NonNull String targetSchema,
    @NonNull List<ImportDataSource> dataSources,
    @NonNull DetectedSchema detectedSchema,
    int chunkSize,
    @NonNull Map<AttributeKey, InvalidValueCoercionStrategy> coercionStrategies
) {
    public ImportRequest(String targetSchema, List<ImportDataSource> dataSources, DetectedSchema detectedSchema) {
        this(targetSchema, dataSources, detectedSchema, 1000, Map.of());
    }

    public ImportRequest {
        chunkSize = chunkSize > 0 ? chunkSize : 1000;
        coercionStrategies = Map.copyOf(coercionStrategies);
    }

    public ImportRequest(String targetSchema, List<ImportDataSource> dataSources, DetectedSchema detectedSchema, int chunkSize) {
        this(targetSchema, dataSources, detectedSchema, chunkSize, Map.of());
    }

    public static Builder forSchema(String targetSchema, DetectedSchema schema) {
        return new Builder(targetSchema, schema);
    }

    public interface CoercionBuilder {
        // In-memory coercions
        void skip();

        void using(InvalidValueCoercionStrategy strategy);

        void useDefaults();

        void useDefault(Object literalValue);

        void nullOnInvalid();

        void throwOnInvalid();

        NumericCoercionBuilder asNumeric();

        // Database-level coercions (execute post-import)
        void forwardFill();

        void backwardFill();

        void useMean();

        void useMedian();

        void useMode();
    }

    @SuppressWarnings("unused")
    public interface NumericCoercionBuilder extends CoercionBuilder {
        void round();

        void round(RoundingMode roundingMode);

        void clamp();

        void clamp(BigDecimal minBound, BigDecimal maxBound);

        void clampPercentage();

        void truncate();
    }

    public record AttributeKey(String rootName, String attributePath) {}

    @With
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    public static class Builder {
        @With(AccessLevel.NONE)
        private final String targetSchema;
        @NonNull
        @With(AccessLevel.NONE)
        private final DetectedSchema detectedSchema;
        private final Map<AttributeKey, InvalidValueCoercionStrategy> coercionStrategies = new HashMap<>();
        private int chunkSize = 1000;

        public Builder withCoercionForAttribute(String rootName, DetectedAttribute attribute, Consumer<CoercionBuilder> coercionConfig) {
            var builder = new CoercionBuilderImpl(this, rootName, attribute);
            coercionConfig.accept(builder);
            return this;
        }

        public Builder withCoercionForPath(String rootName, String attributePath, Consumer<CoercionBuilder> coercionConfig) {
            var attribute = findAttributeByPath(detectedSchema.roots().get(rootName).attributes(), attributePath);
            if (attribute == null) {
                throw new IllegalArgumentException("Attribute not found for path: " + attributePath);
            }
            var builder = new CoercionBuilderImpl(this, rootName, attribute);
            coercionConfig.accept(builder);
            return this;
        }

        private @Nullable DetectedAttribute findAttributeByPath(Map<String, DetectedAttribute> attributes, String path) {
            if (!path.contains(".")) {
                return attributes.get(path);
            }
            var parts = path.split("\\.", 2);
            var first = attributes.get(parts[0]);
            if (first instanceof DetectedAttribute.Composite composite) {
                return findAttributeByPath(composite.subAttributes(), parts[1]);
            }
            return null;
        }

        public ImportRequest importFromSources(List<ImportDataSource> dataSources) {
            if (dataSources.isEmpty()) {
                throw new IllegalStateException("Data sources required");
            }
            return new ImportRequest(
                targetSchema,
                dataSources,
                detectedSchema,
                chunkSize,
                coercionStrategies
            );
        }
    }

    private static class CoercionBuilderImpl implements NumericCoercionBuilder {
        private final Builder parentBuilder;
        private final String rootName;
        private final DetectedAttribute attribute;
        private final boolean isNumeric;

        CoercionBuilderImpl(Builder parentBuilder, String rootName, DetectedAttribute attribute) {
            this.parentBuilder = parentBuilder;
            this.rootName = rootName;
            this.attribute = attribute;
            if (!(attribute instanceof DetectedAttribute.Basic basic)) {
                throw new IllegalArgumentException(
                    "Coercion only supported for basic attributes, got: " + attribute.getClass().getSimpleName()
                );
            }
            this.isNumeric = basic.dataType() instanceof DataType.NumericType;
        }

        @Override
        public void skip() {
            using(com.rorm.dataimport.type.InMemoryCoercion.Skip.INSTANCE);
        }

        @Override
        public void using(InvalidValueCoercionStrategy strategy) {
            var key = new AttributeKey(rootName, attribute.name());
            parentBuilder.coercionStrategies.put(key, strategy);
        }

        @Override
        public void useDefaults() {
            using(com.rorm.dataimport.type.InMemoryCoercion.UseDefault.withStandardDefaults());
        }

        @Override
        public void useDefault(Object literalValue) {
            var basic = (DetectedAttribute.Basic) attribute;
            var dataType = basic.dataType();

            if (dataType == null) {
                throw new IllegalStateException("Cannot set default for attribute without data type: " + attribute.name());
            }

            if (!isCompatibleType(literalValue, dataType)) {
                throw new IllegalArgumentException(
                    "Literal value type " + literalValue.getClass().getSimpleName() +
                    " incompatible with attribute data type " + dataType.getClass().getSimpleName()
                );
            }

            var defaultStrategy = com.rorm.dataimport.type.DefaultValueStrategy.builder()
                .columnDefault(attribute.name(), literalValue)
                .build();

            using(new com.rorm.dataimport.type.InMemoryCoercion.UseDefault(defaultStrategy));
        }

        private boolean isCompatibleType(Object value, DataType dataType) {
            return switch (dataType) {
                case DataType.NumericType _ -> value instanceof Number;
                case DataType.StringType _ -> value instanceof String;
                case DataType.BooleanType _ -> value instanceof Boolean;
                case DataType.DateType _ -> value instanceof java.time.LocalDate;
                case DataType.TimeType _ -> value instanceof java.time.LocalTime;
                case DataType.DateTimeType _ -> value instanceof java.time.Instant;
                case DataType.TimezoneType _ ->
                    value instanceof java.time.ZoneOffset || value instanceof java.time.ZoneId;
                case DataType.DayOfWeekType _ -> value instanceof java.time.DayOfWeek;
                case DataType.EnumType _ -> value instanceof String;
                case DataType.ListType _ -> false;
            };
        }

        @Override
        public void nullOnInvalid() {
            using(com.rorm.dataimport.type.InMemoryCoercion.NullOnInvalid.INSTANCE);
        }

        @Override
        public void throwOnInvalid() {
            using(com.rorm.dataimport.type.InMemoryCoercion.ThrowOnInvalid.INSTANCE);
        }

        @Override
        public void forwardFill() {
            using(com.rorm.dataimport.type.DbLevelCoercion.ForwardFill.INSTANCE);
        }

        @Override
        public void backwardFill() {
            using(com.rorm.dataimport.type.DbLevelCoercion.BackwardFill.INSTANCE);
        }

        @Override
        public void useMean() {
            using(com.rorm.dataimport.type.DbLevelCoercion.UseMean.INSTANCE);
        }

        @Override
        public void useMedian() {
            using(com.rorm.dataimport.type.DbLevelCoercion.UseMedian.INSTANCE);
        }

        @Override
        public void useMode() {
            using(com.rorm.dataimport.type.DbLevelCoercion.UseMode.INSTANCE);
        }

        @Override
        public NumericCoercionBuilder asNumeric() {
            if (!isNumeric) {
                throw new IllegalStateException(
                    "Attribute " + attribute.name() + " is not numeric: " +
                    ((DetectedAttribute.Basic) attribute).dataType().getClass().getSimpleName()
                );
            }
            return this;
        }

        @Override
        public void round() {
            using(new NumericCoercionStrategy.Round());
        }

        @Override
        public void round(RoundingMode roundingMode) {
            using(new NumericCoercionStrategy.Round(roundingMode));
        }

        @Override
        public void clamp() {
            using(new NumericCoercionStrategy.Clamp());
        }

        @Override
        public void clamp(BigDecimal minBound, BigDecimal maxBound) {
            using(new NumericCoercionStrategy.Clamp(minBound, maxBound));
        }

        @Override
        public void clampPercentage() {
            using(NumericCoercionStrategy.Clamp.percentage());
        }

        @Override
        public void truncate() {
            using(new NumericCoercionStrategy.Truncate());
        }
    }
}
