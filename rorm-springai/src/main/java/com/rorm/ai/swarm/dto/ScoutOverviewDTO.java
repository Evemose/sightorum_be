package com.rorm.ai.swarm.dto;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.time.Instant;
import java.util.List;

/**
 * Scout agent output - high-level reconnaissance of data landscape.
 * Guides planner on what research branches to create.
 */
@JsonClassDescription("Initial reconnaissance analysis of the data landscape")
public record ScoutOverviewDTO(

    @JsonPropertyDescription("""
        Primary entities discovered (tables, datasets, key concepts).
        Example: ["customers", "orders", "products", "support_tickets"]
        """)
    @JsonProperty(required = true)
    List<EntitySummary> entities,

    @JsonPropertyDescription("""
        Data quality issues that could impact research reliability.
        Critical for executor to handle edge cases and planner to adjust strategy.
        """)
    @JsonProperty(required = true)
    List<DataQualityIssue> qualityIssues,

    @JsonPropertyDescription("""
        Notable patterns, anomalies, or relationships discovered during reconnaissance.
        Example: "Revenue spike in Q4 2024", "High churn in enterprise segment"
        """)
    @JsonProperty(required = true)
    List<String> patterns,

    @JsonPropertyDescription("""
        Recommended research directions based on what was observed.
        Guides planner on high-value areas to investigate.
        Example: "Investigate correlation between support tickets and churn"
        """)
    @JsonProperty(required = true)
    List<String> recommendations,

    @JsonPropertyDescription("""
        Overall complexity assessment: LOW, MEDIUM, HIGH.
        Helps planner estimate effort and choose appropriate decomposition strategy.
        """)
    @JsonProperty(required = true)
    ComplexityLevel complexity,

    @JsonPropertyDescription("""
        Key constraints or limitations discovered.
        Example: "No data before 2023", "Customer emails contain typos"
        """)
    @JsonProperty(required = true)
    List<String> constraints,

    @JsonPropertyDescription("""
        Timestamp of when scout analysis completed.
        """)
    @JsonProperty(required = true)
    Instant timestamp

) {
    public enum IssueType {
        MISSING_VALUES, DUPLICATES, INCONSISTENT, INVALID_FORMAT, OUTLIERS, OTHER
    }

    public enum Severity {
        CRITICAL, HIGH, MEDIUM, LOW
    }

    public enum ComplexityLevel {
        LOW,      // Simple query, single entity
        MEDIUM,   // Multiple entities, some joins
        HIGH      // Complex relationships, large scale, data quality issues
    }

    @JsonClassDescription("Summary of a discovered entity (table, dataset, concept)")
    public record EntitySummary(
        @JsonPropertyDescription("Entity name (e.g., 'customers', 'orders')")
        @JsonProperty(required = true)
        String name,

        @JsonPropertyDescription("Brief description of what this entity represents")
        @JsonProperty(required = true)
        String description,

        @JsonPropertyDescription("Approximate row count or size indicator")
        @JsonProperty(required = false)
        String size,

        @JsonPropertyDescription("Key fields/columns identified")
        @JsonProperty(required = true)
        List<String> keyFields,

        @JsonPropertyDescription("Relationships to other entities. Example: 'orders.customer_id → customers.id'")
        @JsonProperty(required = false)
        List<String> relationships
    ) {}

    @JsonClassDescription("Data quality issue that may impact research")
    public record DataQualityIssue(
        @JsonPropertyDescription("Type: MISSING_VALUES, DUPLICATES, INCONSISTENT, INVALID_FORMAT, OUTLIERS")
        @JsonProperty(required = true)
        IssueType type,

        @JsonPropertyDescription("Which entity/field is affected")
        @JsonProperty(required = true)
        String affectedEntity,

        @JsonPropertyDescription("Description of the issue")
        @JsonProperty(required = true)
        String description,

        @JsonPropertyDescription("Severity: CRITICAL (blocks research), HIGH (major impact), MEDIUM (minor impact), LOW (negligible)")
        @JsonProperty(required = true)
        Severity severity,

        @JsonPropertyDescription("Estimated percentage of data affected (0.0-1.0)")
        @JsonProperty(required = false)
        Double impactPercentage
    ) {}
}
