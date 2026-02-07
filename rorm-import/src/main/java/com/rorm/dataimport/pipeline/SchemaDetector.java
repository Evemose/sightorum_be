package com.rorm.dataimport.pipeline;

import com.rorm.dataimport.attribute.DetectedAttribute;
import com.rorm.dataimport.hierarchical.HierarchicalDataSource;
import com.rorm.dataimport.override.DetectionOverride;
import com.rorm.dataimport.source.ImportDataSource;
import com.rorm.metamodel.DataType;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.*;

/**
 * Orchestrates schema detection from mixed data sources.
 * <p>
 * This class partitions incoming data sources by type (flat vs hierarchical)
 * and dispatches to the appropriate detection strategy, then merges the results.
 */
@RequiredArgsConstructor
public class SchemaDetector {

    private final FlatDetectionStrategy flatStrategy;
    private final HierarchicalDetectionStrategy hierarchicalStrategy;

    /**
     * Detects schema information from data sources.
     * Supports mixed lists of flat (CSV) and hierarchical (JSON/YAML) sources.
     *
     * @param dataSources          list of data sources to process
     * @param overridesByRoot      map of root name to detection overrides (SchemaOverride or HierarchicalOverride)
     * @param defaultListSeparator default separator for collection attributes
     * @return detected schema containing all roots from all sources
     * @throws IllegalArgumentException if duplicate root names are detected across sources
     */
    public DetectedSchema detectSchema(
        List<ImportDataSource> dataSources,
        Map<String, ? extends List<? extends DetectionOverride>> overridesByRoot,
        String defaultListSeparator
    ) {
        var partitioned = partitionByType(dataSources);

        // Collect ALL root names from ALL sources first, so cross-source references work
        var allRootNames = collectAllRootNames(dataSources);

        DetectedSchema flatSchema = null;
        DetectedSchema hierarchicalSchema = null;

        if (!partitioned.flatSources().isEmpty()) {
            flatSchema = flatStrategy.detect(
                partitioned.flatSources(),
                overridesByRoot,
                defaultListSeparator,
                allRootNames
            );
        }

        if (!partitioned.hierarchicalSources().isEmpty()) {
            hierarchicalSchema = hierarchicalStrategy.detect(
                partitioned.hierarchicalSources(),
                overridesByRoot,
                defaultListSeparator,
                allRootNames
            );
        }

        return mergeSchemas(flatSchema, hierarchicalSchema);
    }

    private PartitionedSources partitionByType(List<ImportDataSource> dataSources) {
        var flatSources = new ArrayList<ImportDataSource>();
        var hierarchicalSources = new ArrayList<HierarchicalDataSource>();

        for (var source : dataSources) {
            if (source instanceof HierarchicalDataSource h) {
                hierarchicalSources.add(h);
            } else {
                flatSources.add(source);
            }
        }

        return new PartitionedSources(flatSources, hierarchicalSources);
    }

    /**
     * Collects all root names from all data sources (both flat and hierarchical).
     * This enables cross-source reference detection.
     */
    private Set<String> collectAllRootNames(List<ImportDataSource> dataSources) {
        var rootNames = new LinkedHashSet<String>();
        for (var source : dataSources) {
            rootNames.add(source.getRootName());
        }
        return rootNames;
    }

    private DetectedSchema mergeSchemas(@Nullable DetectedSchema flatSchema, @Nullable DetectedSchema hierarchicalSchema) {
        if (flatSchema == null && hierarchicalSchema == null) {
            return new DetectedSchema(Map.of());
        }
        if (flatSchema == null) {
            return hierarchicalSchema;
        }
        if (hierarchicalSchema == null) {
            return flatSchema;
        }

        var mergedRoots = mergeRoots(flatSchema, hierarchicalSchema);

        return new DetectedSchema(mergedRoots);
    }

    private static @NonNull LinkedHashMap<String, DetectedRoot> mergeRoots(
        @NonNull DetectedSchema flatSchema,
        @NonNull DetectedSchema hierarchicalSchema
    ) {
        var mergedRoots = new LinkedHashMap<>(flatSchema.roots());

        for (var entry : hierarchicalSchema.roots().entrySet()) {
            var rootName = entry.getKey();
            if (mergedRoots.containsKey(rootName)) {
                throw new IllegalArgumentException(
                    "Duplicate root name detected: '" + rootName + "'. " +
                    "Root names must be unique across all data sources."
                );
            }
            mergedRoots.put(rootName, entry.getValue());
        }
        return mergedRoots;
    }

    private record PartitionedSources(
        List<ImportDataSource> flatSources,
        List<HierarchicalDataSource> hierarchicalSources
    ) {}

    /**
     * Represents a detected root entity during schema detection.
     *
     * @param name             The name of the root (e.g., table name)
     * @param sourceDataSource The ImportDataSource name where this root's data comes from
     * @param attributes       The detected attributes for this root
     * @param idColumn         The detected ID column for this root
     */
    public record DetectedRoot(
        String name,
        String sourceDataSource,
        Map<String, DetectedAttribute> attributes,
        DetectedIdColumn idColumn
    ) {
    }

    public record DetectedIdColumn(
        String attributeName,
        String columnName,
        DataType dataType
    ) {
    }
}
