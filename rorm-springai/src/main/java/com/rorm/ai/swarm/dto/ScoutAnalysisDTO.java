package com.rorm.ai.swarm.dto;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.jspecify.annotations.Nullable;

import java.util.List;

@JsonClassDescription("""
    Tier 1 data landscape map: a breadth-first reconnaissance of the
    dataset described by the metamodel. Records what exists, what is
    missing, and what is dangerous. Does NOT interpret, hypothesize, or
    recommend.
    
    Numeric conventions:
    - Row and attribute counts: integer counts, no unit suffix.
    - Fan-out ratios: dimensionless (child rows / parent rows); never a
      fraction.
    - Rates and proportions: decimal fraction in [0, 1]. A 5% null rate
      is stored as 0.05, NOT 5. Percentages like '5%' must be divided by
      100 on extraction.""")
public record ScoutAnalysisDTO(

    @JsonPropertyDescription("""
        Per-entity census: name, row count, attribute count, summary flags
        (has_temporal, high_null_rate, high_cardinality_categorical, small_table, wide_table),
        key fields, brief description. EVERY entity in the metamodel must be listed.""")
    @JsonProperty(required = true)
    List<EntityCensusEntry> entityCensus,

    @JsonPropertyDescription("""
        Complete FK adjacency list: every relationship edge with cardinality
        (1:1, 1:N, N:M) and the foreign key field name.""")
    @JsonProperty(required = true)
    List<RelationshipEdge> relationshipTopology,

    @JsonPropertyDescription("""
        Geospatial anchor hierarchy (e.g. region(8) -> zone(45) -> location(450)) with
        cardinality per level. Null if no geospatial hierarchy is detected.""")
    @Nullable GeospatialAnchors geospatialAnchors,

    @JsonPropertyDescription("""
        Per-field classification as measurement_of_subject vs measurement_process_metadata,
        with reasoning for ambiguous cases. Only fields that look instrument-related need
        classification.""")
    @JsonProperty(required = true)
    List<MeasurementMetadataAnnotation> measurementMetadataAnnotations,

    @JsonPropertyDescription("""
        Fields that appear to expose information a real-world analyst wouldn't have
        (actual_*, *_true, *_bias, *_fault, simulated_*, *_ground_truth). Empty list if
        none detected.""")
    @JsonProperty(required = true)
    List<IntrinsicColumnFlag> intrinsicColumnFlags,

    @JsonPropertyDescription("""
        Data quality red flags: >20% null rates in important fields, very small tables,
        duplicate indicators, referential integrity issues, temporal gaps. Each with
        severity and quantified impact.""")
    @JsonProperty(required = true)
    List<DataQualityFlag> dataQualityRedFlags,

    @JsonPropertyDescription("""
        1:N cardinality warnings with fan-out ratio > 5. Flags 'N-side table has avg X rows
        per parent - aggregation required before joining to avoid row inflation.'""")
    @JsonProperty(required = true)
    List<CardinalityWarning> cardinalityWarnings,

    @JsonPropertyDescription("""
        Compressed Tier 1 schema summary (~200 tokens): entity names + row counts +
        relationship graph + flags, one line per entity. The scout's most important output.""")
    @JsonProperty(required = true)
    String schemaSummary
) {

    @JsonClassDescription("Census entry for a single entity in the metamodel")
    public record EntityCensusEntry(

        @JsonPropertyDescription("Entity name exactly as it appears in the metamodel")
        @JsonProperty(required = true)
        String name,

        @JsonPropertyDescription("Row count (exact, from COUNT(*)). Unit: rows.")
        @JsonProperty(required = true)
        long rowCount,

        @JsonPropertyDescription("Total number of columns on this entity. Unit: columns.")
        @JsonProperty(required = true)
        int attributeCount,

        @JsonPropertyDescription("""
            Summary flags: subset of [has_temporal, high_null_rate,
            high_cardinality_categorical, small_table, wide_table]. Each flag is a
            boolean marker - include the flag name only when the condition holds.""")
        @JsonProperty(required = true)
        List<String> summaryFlags,

        @JsonPropertyDescription("""
            Key fields on this entity: primary key, foreign keys, target variable
            (if the outcome lives here), and critical operational columns. When the
            entity holds the target, annotate the target column inline with its
            base rate as a decimal fraction in [0, 1] (e.g. 'outcomeCol (TARGET:
            0.065)').""")
        @JsonProperty(required = true)
        List<String> keyFields,

        @JsonPropertyDescription("Brief description of what the entity represents")
        @JsonProperty(required = true)
        String description
    ) {}

    @JsonClassDescription("Foreign-key relationship between two entities")
    public record RelationshipEdge(

        @JsonPropertyDescription("Source (child) entity name")
        @JsonProperty(required = true)
        String fromEntity,

        @JsonPropertyDescription("Target (parent) entity name")
        @JsonProperty(required = true)
        String toEntity,

        @JsonPropertyDescription("Cardinality: 1:1, 1:N, or N:M")
        @JsonProperty(required = true)
        String cardinality,

        @JsonPropertyDescription("Foreign key field carrying the relationship")
        @JsonProperty(required = true)
        String foreignKeyField
    ) {}

    @JsonClassDescription("Location hierarchy identified by pattern matching on field names and cardinality ratios")
    public record GeospatialAnchors(

        @JsonPropertyDescription("""
            Per-level breakdown of the geospatial hierarchy from coarsest to finest.
            Each level carries its fully qualified fields, a distinct-value count,
            and (when small enough) an enumeration of the values.""")
        @JsonProperty(required = true)
        List<HierarchyLevel> levels,

        @JsonPropertyDescription("""
            Cardinality ratios between adjacent levels as human-readable text.
            Format: '<child_count>/<parent_name>' joined with commas, one entry
            per adjacent level pair. Null when there is only one level.""")
        @Nullable String ratios,

        @JsonPropertyDescription("""
            Short narrative describing which entities participate in the hierarchy
            and how the hierarchy was detected (field-name matching, cardinality
            ratios, shared coordinate fields, etc.).""")
        @Nullable String narrative
    ) {

        @JsonClassDescription("One level in the geospatial hierarchy")
        public record HierarchyLevel(

            @JsonPropertyDescription("Human-readable level name, coarsest at the top of the hierarchy.")
            @JsonProperty(required = true)
            String name,

            @JsonPropertyDescription("""
                Fully qualified <entity>.<column> fields at this level. Multiple
                fields allowed when the same level is denormalized across entities.""")
            @JsonProperty(required = true)
            List<String> fields,

            @JsonPropertyDescription("""
                Distinct value count at this level (integer count, not a fraction).""")
            @JsonProperty(required = true)
            long cardinality,

            @JsonPropertyDescription("""
                Optional enumeration of values when cardinality is small enough to
                list. Null when too many values to enumerate.""")
            @Nullable List<String> values
        ) {}
    }

    @JsonClassDescription("Classification of a single field as subject-of-measurement or measurement-process metadata")
    public record MeasurementMetadataAnnotation(

        @JsonPropertyDescription("Entity that owns this field")
        @JsonProperty(required = true)
        String entity,

        @JsonPropertyDescription("Field name")
        @JsonProperty(required = true)
        String field,

        @JsonPropertyDescription("Classification: measurement_of_subject or measurement_process_metadata")
        @JsonProperty(required = true)
        String classification,

        @JsonPropertyDescription("""
            Reasoning for the classification, especially for ambiguous cases. Apply the test:
            'Would replacing this instrument with a different one change the VALUE of this field?'
            If yes -> process metadata; if no -> measurement of subject.""")
        @Nullable String reasoning
    ) {}

    @JsonClassDescription("Field that appears to expose information a real-world analyst wouldn't have")
    public record IntrinsicColumnFlag(

        @JsonPropertyDescription("Entity that owns the suspicious field")
        @JsonProperty(required = true)
        String entity,

        @JsonPropertyDescription("Field name")
        @JsonProperty(required = true)
        String field,

        @JsonPropertyDescription("Reasoning for the flag (e.g. name pattern 'actual_*', model-derived, ground truth label)")
        @JsonProperty(required = true)
        String reasoning
    ) {}

    @JsonClassDescription("A data quality issue with quantified impact and severity")
    public record DataQualityFlag(

        @JsonPropertyDescription("Entity where the issue occurs")
        @JsonProperty(required = true)
        String entity,

        @JsonPropertyDescription("Field where the issue occurs (null if entity-level issue such as small table)")
        @Nullable String field,

        @JsonPropertyDescription("""
            Issue type, one of the canonical tokens: high_null_rate | small_table |
            orphaned_fk | duplicate_rows | temporal_gap | referential_integrity |
            dead_field | impossible_value | parallel_tables | sparse_coverage.""")
        @JsonProperty(required = true)
        String issueType,

        @JsonPropertyDescription("Severity of the issue: LOW | MEDIUM | HIGH")
        @JsonProperty(required = true)
        String severity,

        @JsonPropertyDescription("""
            Quantified impact as a short human-readable string. Must carry both a
            magnitude (count or proportion) and a unit. When proportions are included
            inside the text, write them as percent with the '%' suffix for readability;
            this field is a String so no numeric conversion is enforced.""")
        @JsonProperty(required = true)
        String quantifiedImpact,

        @JsonPropertyDescription("True when the issue blocks analysis; false when it is informational but non-blocking.")
        @JsonProperty(required = true)
        boolean blocksAnalysis
    ) {}

    @JsonClassDescription("1:N relationship with fan-out ratio exceeding 5, requiring aggregation before joining.")
    public record CardinalityWarning(

        @JsonPropertyDescription("Parent (1-side) entity")
        @JsonProperty(required = true)
        String parentEntity,

        @JsonPropertyDescription("Child (N-side) entity")
        @JsonProperty(required = true)
        String childEntity,

        @JsonPropertyDescription("""
            Fan-out ratio: average number of child rows per parent row, computed as
            (child row count) / (parent row count). Dimensionless, always >= 1 for
            1:N relationships, flagged when > 5. Store as a plain number (e.g.
            2667.0), not a percentage, not a fraction.""")
        @JsonProperty(required = true)
        double ratio,

        @JsonPropertyDescription("""
            Aggregation requirement text explaining which grain must be
            produced before the join so joined queries do not inflate row
            counts.""")
        @JsonProperty(required = true)
        String aggregationRequirement
    ) {}
}
