package com.rorm.dataimport.pipeline;

import com.rorm.dataimport.attribute.DetectedAttribute;
import com.rorm.dataimport.pipeline.SchemaDetector.DetectedRoot;
import com.rorm.metamodel.DataType;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds column mappings from DetectedRoot, using source mappings established at detection time.
 */
class ColumnMappingBuilder {

    /**
     * Builds column mappings from a DetectedRoot.
     *
     * @param detectedRoot The detected root containing attributes with source mappings
     * @return List of column mappings with proper source information
     */
    public List<ColumnMapping> buildMappings(DetectedRoot detectedRoot) {
        var mappings = new ArrayList<ColumnMapping>();

        for (var attribute : detectedRoot.attributes().values()) {
            extractMappings(attribute, mappings);
        }

        return mappings;
    }

    private void extractMappings(
        DetectedAttribute attribute,
        List<ColumnMapping> mappings
    ) {
        switch (attribute) {
            case DetectedAttribute.Basic basic -> {
                var source = basic.source();
                mappings.add(new ColumnMapping(
                    source.sourceColumn(), // db column name = source column name
                    source.sourceColumn(),
                    source.dataSourceName(),
                    basic.dataType() != null ? basic.dataType() : new DataType.StringType(),
                    null
                ));
            }
            case DetectedAttribute.Collection coll -> {
                var source = coll.source();
                mappings.add(new ColumnMapping(
                    source.sourceColumn(),
                    source.sourceColumn(),
                    source.dataSourceName(),
                    new DataType.ListType(coll.elementType() != null ? coll.elementType() : new DataType.StringType()),
                    coll.separator()
                ));
            }
            case DetectedAttribute.SingularReference ref -> {
                var source = ref.source();
                mappings.add(new ColumnMapping(
                    source.sourceColumn(),
                    source.sourceColumn(),
                    source.dataSourceName(),
                    ref.dataType() != null ? ref.dataType() : new DataType.NumericType(19, 0),
                    null
                ));
            }
            case DetectedAttribute.PluralReference ref -> {
                var source = ref.source();
                mappings.add(new ColumnMapping(
                    source.sourceColumn(),
                    source.sourceColumn(),
                    source.dataSourceName(),
                    ref.dataType() != null ? ref.dataType() : new DataType.StringType(),
                    null
                ));
            }
            case DetectedAttribute.Composite composite -> {
                for (var subAttr : composite.subAttributes().values()) {
                    extractMappings(subAttr, mappings);
                }
            }
            case DetectedAttribute.OneToOneRoot oneToOne -> {
                // Map the FK column: the ID sub-attribute's source column is the FK in the parent table
                oneToOne.subAttributes().values().stream()
                    .filter(DetectedAttribute.Basic.class::isInstance)
                    .map(DetectedAttribute.Basic.class::cast)
                    .filter(basic -> basic.source().sourceColumn().toLowerCase().endsWith("_id"))
                    .findFirst()
                    .ifPresent(basic -> {
                        var source = basic.source();
                        mappings.add(new ColumnMapping(
                            source.sourceColumn(),
                            source.sourceColumn(),
                            source.dataSourceName(),
                            basic.dataType() != null ? basic.dataType() : new DataType.NumericType(19, 0),
                            null
                        ));
                    });
            }
        }
    }
}
