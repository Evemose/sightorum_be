package com.rorm.dataimport.hierarchical;

import com.rorm.metamodel.DataType;
import org.jspecify.annotations.Nullable;

import java.util.Map;

/**
 * Represents the organically detected structure from hierarchical data.
 * <p>
 * Unlike flat data where structure must be inferred from column naming conventions,
 * hierarchical data provides definitive structural information:
 * <ul>
 *   <li>Single nested object → Composite (embedded) or SingularObjectRef (separate root)</li>
 *   <li>Array of objects → CompositeCollection (embedded) or PluralObjectRef (separate root)</li>
 *   <li>Array of primitives → ScalarArray</li>
 *   <li>Scalar values → Scalar</li>
 * </ul>
 * <p>
 * The distinction between embedded (Composite/CompositeCollection) and reference
 * (SingularObjectRef/PluralObjectRef) is based on ID presence or explicit override.
 */
public record HierarchicalStructure(
    Map<String, DetectedRoot> roots
) {

    /**
     * A field detected from hierarchical data structure.
     */
    public sealed interface DetectedField permits
        DetectedField.Scalar,
        DetectedField.ScalarArray,
        DetectedField.Composite,
        DetectedField.CompositeCollection,
        DetectedField.SingularObjectRef,
        DetectedField.PluralObjectRef {

        String name();

        /**
         * A scalar/primitive field.
         */
        record Scalar(
            String name,
            DataType dataType
        ) implements DetectedField {
        }

        /**
         * An array of scalar values (stored as List in flattened output).
         */
        record ScalarArray(
            String name,
            DataType elementType
        ) implements DetectedField {
        }

        /**
         * A single nested object treated as embedded composite (value object, no identity).
         */
        record Composite(
            String name,
            Map<String, DetectedField> fields
        ) implements DetectedField {
        }

        /**
         * An array of nested objects treated as embedded composites (value objects, no identity).
         */
        record CompositeCollection(
            String name,
            Map<String, DetectedField> elementFields
        ) implements DetectedField {
        }

        /**
         * A single nested object that creates a separate root (one-to-one relationship).
         */
        record SingularObjectRef(
            String name,
            String targetRootName
        ) implements DetectedField {
        }

        /**
         * An array of objects that creates a separate root (one-to-many relationship).
         */
        record PluralObjectRef(
            String name,
            String targetRootName
        ) implements DetectedField {
        }
    }

    /**
     * A root entity detected from hierarchical data.
     * The primary root comes from the top-level array/object.
     * Additional roots are created from nested objects interpreted as separate roots.
     */
    public record DetectedRoot(
        String name,
        Map<String, DetectedField> fields,
        @Nullable String parentRootName,
        @Nullable String parentFieldName
    ) {
        public static DetectedRoot primary(String name, Map<String, DetectedField> fields) {
            return new DetectedRoot(name, fields, null, null);
        }

        public static DetectedRoot child(String name, Map<String, DetectedField> fields,
                                         String parentRootName, String parentFieldName) {
            return new DetectedRoot(name, fields, parentRootName, parentFieldName);
        }

        public boolean isPrimary() {
            return parentRootName == null;
        }
    }
}
