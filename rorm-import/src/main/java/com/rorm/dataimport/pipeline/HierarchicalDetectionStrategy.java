package com.rorm.dataimport.pipeline;

import com.rorm.dataimport.hierarchical.HierarchicalDataSource;
import com.rorm.dataimport.hierarchical.HierarchicalOverride;
import com.rorm.dataimport.hierarchical.HierarchicalSchemaConverter;
import com.rorm.dataimport.override.DetectionOverride;
import com.rorm.dataimport.source.ImportDataSource;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Detection strategy for hierarchical (JSON/YAML) data sources.
 * <p>
 * Unlike flat data sources which require heuristics to determine structure,
 * hierarchical sources can detect their structure organically from the data.
 * This strategy delegates to the existing HierarchicalSchemaConverter.
 */
@Component
@RequiredArgsConstructor
public class HierarchicalDetectionStrategy implements DetectionStrategy<HierarchicalDataSource> {

    private final HierarchicalSchemaConverter converter;

    @Override
    public boolean canHandle(ImportDataSource dataSource) {
        return dataSource instanceof HierarchicalDataSource;
    }

    @Override
    public DetectedSchema detect(
        List<HierarchicalDataSource> dataSources,
        Map<String, ? extends List<? extends DetectionOverride>> overridesByRoot,
        String defaultListSeparator,
        Set<String> allRootNames
    ) {
        var hierarchicalOverridesByRoot = filterHierarchicalOverrides(overridesByRoot);

        var allRoots = new LinkedHashMap<String, SchemaDetector.DetectedRoot>();

        for (var source : dataSources) {
            var structure = source.detectStructure();
            var rootOverrides = hierarchicalOverridesByRoot.getOrDefault(source.getRootName(), List.of());
            var schema = converter.convert(structure, source.getRootName(), allRootNames, rootOverrides);
            allRoots.putAll(schema.roots());
        }

        return new DetectedSchema(allRoots);
    }

    private Map<String, List<HierarchicalOverride>> filterHierarchicalOverrides(
        Map<String, ? extends List<? extends DetectionOverride>> overridesByRoot
    ) {
        var result = new HashMap<String, List<HierarchicalOverride>>();
        for (var entry : overridesByRoot.entrySet()) {
            var hierarchicalOverrides = entry.getValue().stream()
                .filter(o -> o instanceof HierarchicalOverride)
                .map(o -> (HierarchicalOverride) o)
                .toList();
            if (!hierarchicalOverrides.isEmpty()) {
                result.put(entry.getKey(), hierarchicalOverrides);
            }
        }
        return result;
    }
}
