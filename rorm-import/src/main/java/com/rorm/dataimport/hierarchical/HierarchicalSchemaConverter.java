package com.rorm.dataimport.hierarchical;

import com.rorm.dataimport.attribute.DetectedAttribute;
import com.rorm.dataimport.hierarchical.HierarchicalStructure.DetectedField;
import com.rorm.dataimport.hierarchical.HierarchicalStructure.DetectedRoot;
import com.rorm.dataimport.pipeline.DetectedSchema;
import com.rorm.dataimport.pipeline.SchemaDetector.DetectedIdColumn;
import com.rorm.dataimport.pipeline.SourceMapping;
import com.rorm.metamodel.DataType;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Converts organically detected hierarchical structure to the standard DetectedSchema format.
 * <p>
 * Unlike flat data detection which requires heuristics and overrides to determine structure,
 * hierarchical data provides definitive structural information that is simply converted
 * to the common schema format used by the import pipeline.
 */
public class HierarchicalSchemaConverter {

    private static final String DEFAULT_ID_COLUMN = "id";
    private static final String SYNTHETIC_ID_SUFFIX = "_synthetic_id";

    /**
     * Converts a HierarchicalStructure to DetectedSchema for use with the import pipeline.
     *
     * @param structure      The organically detected hierarchical structure
     * @param dataSourceName The name of the data source (typically file name without extension)
     * @return DetectedSchema compatible with the import pipeline
     */
    public DetectedSchema convert(HierarchicalStructure structure, String dataSourceName) {
        var detectedRoots = new LinkedHashMap<String, com.rorm.dataimport.pipeline.SchemaDetector.DetectedRoot>();

        for (var entry : structure.roots().entrySet()) {
            var rootName = entry.getKey();
            var hierarchicalRoot = entry.getValue();
            var detectedRoot = convertRoot(hierarchicalRoot, dataSourceName);
            detectedRoots.put(rootName, detectedRoot);
        }

        return new DetectedSchema(detectedRoots);
    }

    private com.rorm.dataimport.pipeline.SchemaDetector.DetectedRoot convertRoot(
        DetectedRoot hierarchicalRoot,
        String dataSourceName
    ) {
        var attributes = new LinkedHashMap<String, DetectedAttribute>();
        convertFields(hierarchicalRoot.fields(), "", attributes, dataSourceName);

        // Determine ID column
        var idColumn = detectIdColumn(hierarchicalRoot);

        // For child roots, add parent reference
        if (!hierarchicalRoot.isPrimary()) {
            var parentRootName = Objects.requireNonNull(hierarchicalRoot.parentRootName());
            var parentRefName = parentRootName + "_id";
            var parentRefSource = new SourceMapping(dataSourceName, parentRefName);
            attributes.put(parentRefName, new DetectedAttribute.SingularReference(
                parentRefName,
                parentRefSource,
                parentRootName,
                new DataType.NumericType(19, 0) // Long FK
            ));
        }

        return new com.rorm.dataimport.pipeline.SchemaDetector.DetectedRoot(
            hierarchicalRoot.name(),
            dataSourceName,
            attributes,
            idColumn
        );
    }

    private void convertFields(
        Map<String, DetectedField> fields,
        String prefix,
        Map<String, DetectedAttribute> attributes,
        String dataSourceName
    ) {
        for (var entry : fields.entrySet()) {
            var fieldName = entry.getKey();
            var field = entry.getValue();
            var fullPath = prefix.isEmpty() ? fieldName : prefix + "." + fieldName;

            var attribute = convertField(field, fullPath, dataSourceName);
            attributes.put(fieldName, attribute);
        }
    }

    private DetectedAttribute convertField(DetectedField field, String sourcePath, String dataSourceName) {
        var source = new SourceMapping(dataSourceName, sourcePath);

        return switch (field) {
            case DetectedField.Scalar scalar -> new DetectedAttribute.Basic(
                scalar.name(),
                source,
                scalar.dataType()
            );

            case DetectedField.ScalarArray array -> new DetectedAttribute.Collection(
                array.name(),
                source,
                ",", // Default separator for database storage
                array.elementType()
            );

            case DetectedField.Composite composite -> {
                var subAttributes = new LinkedHashMap<String, DetectedAttribute>();
                convertFields(composite.fields(), sourcePath, subAttributes, dataSourceName);
                yield new DetectedAttribute.Composite(composite.name(), subAttributes);
            }

            case DetectedField.CompositeCollection coll -> {
                // CompositeCollection maps to a Collection of composites
                // For now, we treat it similar to Composite but mark it as a collection
                var subAttributes = new LinkedHashMap<String, DetectedAttribute>();
                convertFields(coll.elementFields(), sourcePath, subAttributes, dataSourceName);
                yield new DetectedAttribute.Composite(coll.name(), subAttributes);
            }

            case DetectedField.SingularObjectRef ref -> new DetectedAttribute.SingularReference(
                ref.name(),
                source,
                ref.targetRootName(),
                new DataType.NumericType(19, 0) // Long FK
            );

            case DetectedField.PluralObjectRef ref -> new DetectedAttribute.PluralReference(
                ref.name(),
                source,
                ref.targetRootName(),
                new DataType.NumericType(19, 0) // FK type
            );
        };
    }

    private DetectedIdColumn detectIdColumn(DetectedRoot root) {
        // Check if there's an explicit "id" field
        var idField = root.fields().get(DEFAULT_ID_COLUMN);
        if (idField instanceof DetectedField.Scalar scalar) {
            return new DetectedIdColumn(DEFAULT_ID_COLUMN, DEFAULT_ID_COLUMN, scalar.dataType());
        }

        // For child roots, use synthetic ID based on parent relationship
        if (!root.isPrimary()) {
            var syntheticIdName = root.name() + SYNTHETIC_ID_SUFFIX;
            return new DetectedIdColumn(syntheticIdName, syntheticIdName, new DataType.NumericType(19, 0));
        }

        // Default: create synthetic numeric ID
        var syntheticIdName = root.name() + SYNTHETIC_ID_SUFFIX;
        return new DetectedIdColumn(syntheticIdName, syntheticIdName, new DataType.NumericType(19, 0));
    }
}
