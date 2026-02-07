package com.rorm.dataimport.hierarchical;

import com.rorm.dataimport.attribute.DetectedAttribute;
import com.rorm.dataimport.hierarchical.HierarchicalOverride.DataTypeOverride;
import com.rorm.dataimport.hierarchical.HierarchicalStructure.DetectedField;
import com.rorm.dataimport.pipeline.SourceMapping;
import com.rorm.metamodel.DataType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Converts DetectedField instances to DetectedAttribute instances.
 */
class FieldConverter {

    private static final String DEFAULT_SEPARATOR = ",";

    private final OverrideFinder overrideFinder;
    private final ExternalReferenceDetector referenceDetector;

    FieldConverter(OverrideFinder overrideFinder, ExternalReferenceDetector referenceDetector) {
        this.overrideFinder = overrideFinder;
        this.referenceDetector = referenceDetector;
    }

    DetectedAttribute convertScalar(
        DetectedField.Scalar scalar,
        String sourcePath,
        SourceMapping source,
        Set<String> allRootNames,
        String currentRootName,
        List<HierarchicalOverride> overrides
    ) {
        var dataType = overrideFinder.findOverride(overrides, sourcePath, DataTypeOverride.class)
            .map(DataTypeOverride::dataType)
            .orElse(scalar.dataType());

        var externalRef = referenceDetector.detectExternalReference(scalar.name(), allRootNames, currentRootName);
        if (externalRef.isPresent()) {
            return new DetectedAttribute.SingularReference(
                toCamelCase(scalar.name()),
                source,
                externalRef.get(),
                dataType
            );
        }

        return new DetectedAttribute.Basic(scalar.name(), source, dataType);
    }

    private String toCamelCase(String snakeCase) {
        var parts = snakeCase.split("_");
        var sb = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            if (!parts[i].isEmpty()) {
                sb.append(Character.toUpperCase(parts[i].charAt(0)));
                if (parts[i].length() > 1) {
                    sb.append(parts[i].substring(1));
                }
            }
        }
        return sb.toString();
    }

    DetectedAttribute convertScalarArray(
        DetectedField.ScalarArray array,
        String sourcePath,
        SourceMapping source,
        List<HierarchicalOverride> overrides
    ) {
        var elementType = overrideFinder.findOverride(overrides, sourcePath, DataTypeOverride.class)
            .map(DataTypeOverride::dataType)
            .orElse(array.elementType());

        return new DetectedAttribute.Collection(array.name(), source, DEFAULT_SEPARATOR, elementType);
    }

    DetectedAttribute convertComposite(
        DetectedField.Composite composite,
        String sourcePath,
        String dataSourceName,
        Set<String> allRootNames,
        String currentRootName,
        List<HierarchicalOverride> overrides,
        FieldConversionContext context
    ) {
        var subAttributes = new LinkedHashMap<String, DetectedAttribute>();
        context.convertFields(
            composite.fields(),
            sourcePath,
            subAttributes,
            dataSourceName,
            allRootNames,
            currentRootName,
            overrides
        );
        return new DetectedAttribute.Composite(composite.name(), subAttributes);
    }

    DetectedAttribute convertCompositeCollection(
        DetectedField.CompositeCollection coll,
        String sourcePath,
        String dataSourceName,
        Set<String> allRootNames,
        String currentRootName,
        List<HierarchicalOverride> overrides,
        FieldConversionContext context
    ) {
        var subAttributes = new LinkedHashMap<String, DetectedAttribute>();
        context.convertFields(
            coll.elementFields(),
            sourcePath,
            subAttributes,
            dataSourceName,
            allRootNames,
            currentRootName,
            overrides
        );
        return new DetectedAttribute.Composite(coll.name(), subAttributes);
    }

    DetectedAttribute convertSingularObjectRef(
        DetectedField.SingularObjectRef ref,
        SourceMapping source
    ) {
        return new DetectedAttribute.SingularReference(
            ref.name(),
            source,
            ref.targetRootName(),
            new DataType.NumericType(19, 0)
        );
    }

    DetectedAttribute convertPluralObjectRef(
        DetectedField.PluralObjectRef ref,
        SourceMapping source
    ) {
        return new DetectedAttribute.PluralReference(
            ref.name(),
            source,
            ref.targetRootName(),
            new DataType.NumericType(19, 0)
        );
    }

    /**
     * Context interface for recursive field conversion.
     */
    interface FieldConversionContext {
        void convertFields(
            Map<String, DetectedField> fields,
            String prefix,
            Map<String, DetectedAttribute> attributes,
            String dataSourceName,
            Set<String> allRootNames,
            String currentRootName,
            List<HierarchicalOverride> overrides
        );
    }
}
