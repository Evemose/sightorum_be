package com.rorm.ml.dto.pipelinespec;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;

@JsonNaming(SnakeCaseStrategy.class)
@JsonClassDescription("""
    Positivity check for categorical treatments with many levels. Cell
    counts are computed over treatment levels x confounder strata; cells
    below minCellThreshold are trimmed. If surviving coverage is less
    than minCoveragePct of the original N, the treatment is coarsened
    one level along treatmentHierarchy and re-checked.""")
public record PositivityCheck(

    @JsonPropertyDescription("Highest-cardinality discrete W variable defining the confounder strata.")
    @JsonProperty(required = true)
    String confounderColumn,

    @JsonPropertyDescription("""
        Expected cell size = N / (treatment_levels * confounder_strata).
        Integer row count.""")
    @JsonProperty(required = true)
    int expectedCellSize,

    @JsonPropertyDescription("""
        Minimum cell size below which a cell is trimmed. Integer row
        count; typically expectedCellSize / 3.""")
    @JsonProperty(required = true)
    int minCellThreshold,

    @JsonPropertyDescription("""
        Treatment operationalizations ordered from finest to coarsest.
        Coarsening begins at the finest level and proceeds one step at a
        time until coverage meets minCoveragePct.""")
    @JsonProperty(required = true)
    List<String> treatmentHierarchy,

    @JsonPropertyDescription("""
        Minimum surviving sample coverage required to accept a
        coarsening. Decimal fraction in (0, 1]; 0.50 means 'at least 50%
        of rows must remain after trimming'.""")
    @JsonProperty(required = true)
    double minCoveragePct
) {}
