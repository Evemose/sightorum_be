package com.rorm.dataimport.attribute;

import java.util.Map;

public sealed interface DetectedAttribute permits
    DetectedAttribute.Basic,
    DetectedAttribute.Composite,
    DetectedAttribute.SingularReference,
    DetectedAttribute.PluralReference,
    DetectedAttribute.Collection,
    DetectedAttribute.OneToOneRoot {

    String name();

    record Basic(
        String name,
        String columnName,
        String type
    ) implements DetectedAttribute {
    }

    record Composite(
        String name,
        Map<String, DetectedAttribute> subAttributes
    ) implements DetectedAttribute {
    }

    record SingularReference(
        String name,
        String columnName,
        String targetRootName
    ) implements DetectedAttribute {
    }

    record PluralReference(
        String name,
        String columnName,
        String targetRootName
    ) implements DetectedAttribute {
    }

    record Collection(
        String name,
        String columnName,
        String separator
    ) implements DetectedAttribute {
    }

    /**
     * Represents a one-to-one relationship that creates a new root.
     * The parent root will have a singular reference to the new root.
     */
    record OneToOneRoot(
        String name,
        String targetRootName,
        Map<String, DetectedAttribute> subAttributes
    ) implements DetectedAttribute {
    }
}
