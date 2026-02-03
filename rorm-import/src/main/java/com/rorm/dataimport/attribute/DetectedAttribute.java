package com.rorm.dataimport.attribute;

import com.rorm.dataimport.pipeline.SourceMapping;
import com.rorm.metamodel.DataType;
import org.jspecify.annotations.Nullable;

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
        SourceMapping source,
        @Nullable DataType dataType
    ) implements DetectedAttribute {
    }

    record Composite(
        String name,
        Map<String, DetectedAttribute> subAttributes
    ) implements DetectedAttribute {
    }

    record SingularReference(
        String name,
        SourceMapping source,
        String targetRootName,
        @Nullable DataType dataType
    ) implements DetectedAttribute {
    }

    record PluralReference(
        String name,
        SourceMapping source,
        String targetRootName,
        @Nullable DataType dataType
    ) implements DetectedAttribute {
    }

    record Collection(
        String name,
        SourceMapping source,
        String separator,
        @Nullable DataType elementType
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
