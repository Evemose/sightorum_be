package com.rorm.dataimport.pipeline.profile;

import com.rorm.engine.TypeCategory;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Aggregate root for pre-computed schema analysis.
 * <p>
 * Tier 1 (always in context): entity names, row counts, flags, relationships (~200 tokens).
 * Tier 2 (per entity): attribute list with types and categories.
 * Tier 3 (per attribute): full distribution statistics.
 */
public record SchemaProfile(
    List<EntityProfile> entities,
    List<Relationship> relationships
) {

    public Optional<EntityProfile> findEntity(String name) {
        return entities.stream().filter(e -> e.name().equals(name)).findFirst();
    }

    public enum Cardinality {MANY_TO_ONE, ONE_TO_MANY}

    public enum SummaryFlag {
        HAS_TEMPORAL,
        HAS_NUMERIC,
        HAS_CATEGORICAL,
        HAS_BOOLEAN,
        HIGH_CARDINALITY,
        SPARSE_DATA,
        SMALL_DATASET,
        LARGE_DATASET
    }

    public record EntityProfile(
        String name,
        long rowCount,
        Set<SummaryFlag> flags,
        List<AttributeProfile> attributes
    ) {
        public Optional<AttributeProfile> findAttribute(String name) {
            return attributes.stream().filter(a -> a.name().equals(name)).findFirst();
        }
    }

    public record AttributeProfile(
        String name,
        String dataTypeName,
        TypeCategory category,
        AttributeStatistics statistics
    ) {}

    public record Relationship(
        String source,
        String target,
        String attribute,
        Cardinality cardinality
    ) {}
}
