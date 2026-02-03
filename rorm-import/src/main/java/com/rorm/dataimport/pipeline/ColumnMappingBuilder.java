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
                    basic.dataType() != null ? basic.dataType() : new DataType.StringType()
                ));
            }
            case DetectedAttribute.Collection coll -> {
                var source = coll.source();
                mappings.add(new ColumnMapping(
                    source.sourceColumn(),
                    source.sourceColumn(),
                    source.dataSourceName(),
                    coll.elementType() != null ? coll.elementType() : new DataType.StringType()
                ));
            }
            case DetectedAttribute.SingularReference ref -> {
                var source = ref.source();
                mappings.add(new ColumnMapping(
                    source.sourceColumn(),
                    source.sourceColumn(),
                    source.dataSourceName(),
                    ref.dataType() != null ? ref.dataType() : new DataType.NumericType(19, 0)
                ));
            }
            case DetectedAttribute.PluralReference ref -> {
                var source = ref.source();
                mappings.add(new ColumnMapping(
                    source.sourceColumn(),
                    source.sourceColumn(),
                    source.dataSourceName(),
                    ref.dataType() != null ? ref.dataType() : new DataType.StringType()
                ));
            }
            case DetectedAttribute.Composite composite -> {
                for (var subAttr : composite.subAttributes().values()) {
                    extractMappings(subAttr, mappings);
                }
            }
            case DetectedAttribute.OneToOneRoot _ -> {
                // OneToOneRoot creates a separate root - handled separately
            }
        }
    }
}
