package com.rorm.viz.dto.data;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.jspecify.annotations.Nullable;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonClassDescription("Payload for per-bucket-rate histograms: named buckets, each with a count.")
public record PerBucketRateData(

    @JsonPropertyDescription("Buckets in render order (left-to-right).")
    @JsonProperty(required = true)
    List<Bin> bins
) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonClassDescription("One bucket with a label and count.")
    public record Bin(

        @JsonPropertyDescription("Bucket key. Also the default visible label.")
        @JsonProperty(required = true)
        String key,

        @JsonPropertyDescription("Number of samples in this bucket.")
        @JsonProperty(required = true)
        long count,

        @JsonPropertyDescription("Optional per-bucket color role.")
        @Nullable String color
    ) {}
}
