package com.rorm.dataimport.pipeline.profile;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.math.BigDecimal;
import java.util.List;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = AttributeStatistics.NumericStatistics.class, name = "numeric"),
    @JsonSubTypes.Type(value = AttributeStatistics.CategoricalStatistics.class, name = "categorical"),
    @JsonSubTypes.Type(value = AttributeStatistics.TemporalStatistics.class, name = "temporal"),
    @JsonSubTypes.Type(value = AttributeStatistics.BooleanStatistics.class, name = "boolean"),
    @JsonSubTypes.Type(value = AttributeStatistics.ReferenceStatistics.class, name = "reference"),
    @JsonSubTypes.Type(value = AttributeStatistics.BasicStatistics.class, name = "basic")
})
public sealed interface AttributeStatistics {

    long totalCount();

    long nullCount();

    long distinctCount();

    record NumericStatistics(
        long totalCount, long nullCount, long distinctCount,
        BigDecimal min, BigDecimal max, BigDecimal mean,
        BigDecimal stddev, BigDecimal sum
    ) implements AttributeStatistics {}

    record CategoricalStatistics(
        long totalCount, long nullCount, long distinctCount,
        List<ValueFrequency> topValues
    ) implements AttributeStatistics {}

    record TemporalStatistics(
        long totalCount, long nullCount, long distinctCount,
        String earliest, String latest
    ) implements AttributeStatistics {}

    record BooleanStatistics(
        long totalCount, long nullCount, long distinctCount,
        long trueCount, long falseCount
    ) implements AttributeStatistics {}

    record ReferenceStatistics(
        long totalCount, long nullCount, long distinctCount,
        String targetEntity
    ) implements AttributeStatistics {}

    record BasicStatistics(
        long totalCount, long nullCount, long distinctCount
    ) implements AttributeStatistics {}

    record ValueFrequency(String value, long count) {}
}
