package com.rorm.dataimport.pipeline;

import com.rorm.dataimport.override.DetectionOverride;
import com.rorm.dataimport.source.ImportDataSource;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Strategy interface for schema detection from data sources.
 * <p>
 * Different data source types (flat CSV vs hierarchical JSON/YAML) require
 * different detection approaches. This interface allows the SchemaDetector
 * to dispatch to the appropriate strategy based on the data source type.
 *
 * @param <S> the type of data source this strategy handles
 */
public interface DetectionStrategy<S extends ImportDataSource> {

    /**
     * Determines if this strategy can handle the given data source.
     *
     * @param dataSource the data source to check
     * @return true if this strategy can process the data source
     */
    boolean canHandle(ImportDataSource dataSource);

    /**
     * Detects schema from the provided data sources.
     *
     * @param dataSources          list of data sources to process
     * @param overridesByRoot      map of root name to overrides (strategy filters relevant types)
     * @param defaultListSeparator default separator for collection attributes
     * @param allRootNames         set of all root names from all sources (for cross-source reference detection)
     * @return detected schema containing all roots from the data sources
     */
    DetectedSchema detect(
        List<S> dataSources,
        Map<String, ? extends List<? extends DetectionOverride>> overridesByRoot,
        String defaultListSeparator,
        Set<String> allRootNames
    );
}
