package com.rorm.ml.dto.pipelinespec;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import org.jspecify.annotations.Nullable;

import java.util.List;

@JsonNaming(SnakeCaseStrategy.class)
@JsonClassDescription("""
    Structural-break detection config using the PELT changepoint algorithm.
    Multiple entries may be configured at different granularities to
    detect both coarse regime changes and fine-grained shifts.""")
public record StructuralBreakConfig(

    @JsonPropertyDescription("Unique id for this structural-break config within the spec.")
    @JsonProperty(required = true)
    String id,

    @JsonPropertyDescription("""
        Column identifying the entity over which breaks are detected
        per-entity. Must be in the query SELECT and not in stripColumns.""")
    @JsonProperty(required = true)
    String entityColumn,

    @JsonPropertyDescription("""
        Temporal column used to order observations within each entity.
        Must be in the query SELECT and not in stripColumns.""")
    @JsonProperty(required = true)
    String temporalColumn,

    @JsonPropertyDescription("""
        Temporal grain for aggregation: a valid pandas period frequency
        string starting with D (daily), W (weekly), M (monthly), Q
        (quarterly), or Y (yearly).""")
    @JsonProperty(required = true)
    String temporalGrain,

    @JsonPropertyDescription("""
        PELT penalty parameter. Dimensionless. Must be strictly > 0; the
        algorithm produces spurious breaks at non-positive values. A
        sensitive variant typically uses ln(T); a conservative variant
        uses 3 * ln(T), where T is the number of temporal points per
        entity.""")
    @JsonProperty(required = true)
    double peltPenalty,

    @JsonPropertyDescription("Minimum observations required per entity-period. Must be > 0.")
    @JsonProperty(required = true)
    int minObsPerPeriod,

    @JsonPropertyDescription("""
        Optional list of table names carrying known events to overlay on
        detected break locations. Null when no known-event tables are
        provided.""")
    @Nullable List<String> knownEventsTables,

    @JsonPropertyDescription("Number of distinct entities at this granularity.")
    @JsonProperty(required = true)
    int entityCount,

    @JsonPropertyDescription("Number of distinct temporal points at this granularity.")
    @JsonProperty(required = true)
    int temporalPoints
) {}
