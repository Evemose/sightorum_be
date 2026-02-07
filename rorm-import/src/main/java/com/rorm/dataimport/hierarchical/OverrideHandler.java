package com.rorm.dataimport.hierarchical;

import com.rorm.dataimport.attribute.DetectedAttribute;
import com.rorm.dataimport.hierarchical.HierarchicalOverride.ForceBasic;
import com.rorm.dataimport.hierarchical.HierarchicalOverride.ForceReference;
import com.rorm.dataimport.hierarchical.HierarchicalStructure.DetectedField;
import com.rorm.dataimport.pipeline.SourceMapping;

import java.util.Optional;
import java.util.Set;

/**
 * Handles application of hierarchical overrides to detected fields.
 */
class OverrideHandler {

    private static final String DEFAULT_SEPARATOR = ",";

    /**
     * Applies ForceReference override to convert scalar fields to references.
     */
    Optional<DetectedAttribute> applyForceReference(
        DetectedField field,
        ForceReference override,
        SourceMapping source,
        Set<String> allRootNames,
        String sourcePath
    ) {
        validateTargetRootExists(override.targetRootName(), allRootNames, sourcePath);

        return switch (field) {
            case DetectedField.Scalar scalar -> Optional.of(new DetectedAttribute.SingularReference(
                scalar.name(),
                source,
                override.targetRootName(),
                scalar.dataType()
            ));
            case DetectedField.ScalarArray array -> Optional.of(new DetectedAttribute.PluralReference(
                array.name(),
                source,
                override.targetRootName(),
                array.elementType()
            ));
            default -> Optional.empty();
        };
    }

    private void validateTargetRootExists(String targetRootName, Set<String> allRootNames, String sourcePath) {
        if (!allRootNames.isEmpty() && !allRootNames.contains(targetRootName)) {
            throw new IllegalArgumentException(
                "ForceReference at '" + sourcePath + "' references non-existent root '" +
                targetRootName + "'. Available roots: " + allRootNames
            );
        }
    }

    /**
     * Applies ForceBasic override to convert objects/references to scalar attributes.
     */
    Optional<DetectedAttribute> applyForceBasic(
        DetectedField field,
        ForceBasic override,
        SourceMapping source
    ) {
        return switch (field) {
            case DetectedField.Composite composite -> Optional.of(new DetectedAttribute.Basic(
                composite.name(),
                source,
                override.dataType()
            ));
            case DetectedField.SingularObjectRef ref -> Optional.of(new DetectedAttribute.Basic(
                ref.name(),
                source,
                override.dataType()
            ));
            case DetectedField.CompositeCollection coll -> Optional.of(new DetectedAttribute.Collection(
                coll.name(),
                source,
                DEFAULT_SEPARATOR,
                override.dataType()
            ));
            case DetectedField.PluralObjectRef ref -> Optional.of(new DetectedAttribute.Collection(
                ref.name(),
                source,
                DEFAULT_SEPARATOR,
                override.dataType()
            ));
            default -> Optional.empty();
        };
    }
}
