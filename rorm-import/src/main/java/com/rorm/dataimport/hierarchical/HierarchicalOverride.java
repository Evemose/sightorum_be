package com.rorm.dataimport.hierarchical;

import com.rorm.dataimport.override.DetectionOverride;
import com.rorm.metamodel.DataType;
import org.jspecify.annotations.Nullable;

/**
 * Limited overrides for hierarchical data import.
 * <p>
 * Unlike flat data which may require structural overrides (composite definitions,
 * reference targets, etc.), hierarchical data has organic structure that is
 * mostly self-describing. Only limited overrides are allowed:
 * <ul>
 *   <li>Data type refinements (e.g., force enum, specify precision)</li>
 *   <li>Force nested object to be treated as composite (embedded)</li>
 *   <li>Force nested object to be treated as separate root (with ID specification)</li>
 * </ul>
 */
public sealed interface HierarchicalOverride extends DetectionOverride permits
    HierarchicalOverride.DataTypeOverride,
    HierarchicalOverride.ForceComposite,
    HierarchicalOverride.ForceSeparateRoot,
    HierarchicalOverride.ForceBasic,
    HierarchicalOverride.ForceReference,
    HierarchicalOverride.IdOverride {

    /**
     * Path to the field being overridden.
     * Uses dot notation for nested fields: "address.zipcode", "items.price"
     */
    String fieldPath();

    /**
     * Strategy for determining the ID of a nested object when forcing it to be a separate root.
     */
    sealed interface IdStrategy permits IdStrategy.UseField, IdStrategy.AutoGenerate {

        /**
         * Use an existing field from the nested object as the ID.
         */
        record UseField(String fieldName, @Nullable DataType dataType) implements IdStrategy {
            public UseField(String fieldName) {
                this(fieldName, null);
            }
        }

        /**
         * Auto-generate a synthetic numeric ID.
         */
        record AutoGenerate() implements IdStrategy {
            public static final AutoGenerate INSTANCE = new AutoGenerate();
        }
    }

    /**
     * Override the detected data type for a scalar field.
     */
    record DataTypeOverride(
        String fieldPath,
        DataType dataType
    ) implements HierarchicalOverride {
    }

    /**
     * Force a nested object (or array of objects) to be treated as embedded composite.
     * <p>
     * Use when nested objects have an "id" field but should still be embedded
     * as value objects rather than creating a separate root.
     * <p>
     * Applies to:
     * <ul>
     *   <li>Single nested object → Composite</li>
     *   <li>Array of objects → CompositeCollection</li>
     * </ul>
     */
    record ForceComposite(
        String fieldPath
    ) implements HierarchicalOverride {
    }

    /**
     * Force a nested object (or array of objects) to be treated as a separate root.
     * <p>
     * Use when nested objects don't have an "id" field but should be extracted
     * as a separate entity with a relationship.
     * <p>
     * Applies to:
     * <ul>
     *   <li>Single nested object → SingularObjectRef (one-to-one)</li>
     *   <li>Array of objects → PluralObjectRef (one-to-many)</li>
     * </ul>
     */
    record ForceSeparateRoot(
        String fieldPath,
        IdStrategy idStrategy
    ) implements HierarchicalOverride {
    }

    /**
     * Force a nested object or reference to be treated as a basic/scalar attribute.
     * <p>
     * Use when a nested object should be stored as a single scalar value instead of
     * a composite or reference. This can extract a specific field from the object
     * or use a default field (like "id").
     * <p>
     * Examples:
     * <ul>
     *   <li>Store only the ID: ForceBasic("author", "id", NumericType)</li>
     *   <li>Store only the name: ForceBasic("category", "name", StringType)</li>
     *   <li>Store the whole object as JSON: ForceBasic("metadata", null, StringType)</li>
     * </ul>
     */
    record ForceBasic(
        String fieldPath,
        DataType dataType
    ) implements HierarchicalOverride {
    }

    /**
     * Force a scalar field to be treated as a reference to another root.
     * <p>
     * Use when a scalar value (like an ID or code) should be interpreted as
     * a foreign key reference to another entity.
     * <p>
     * Applies to:
     * <ul>
     *   <li>Single scalar value → SingularReference</li>
     *   <li>Array of scalar values → PluralReference</li>
     * </ul>
     */
    record ForceReference(
        String fieldPath,
        String targetRootName
    ) implements HierarchicalOverride {
    }

    /**
     * Override the ID column configuration for a root.
     * <p>
     * Use when you want to specify which field should be used as the ID
     * for a root entity, or change the data type of the detected ID.
     * <p>
     * If fieldName is null, uses the detected ID field but changes its type.
     */
    record IdOverride(
        String rootName,
        @Nullable String fieldName,
        @Nullable DataType dataType
    ) implements HierarchicalOverride {
        @Override
        public String fieldPath() {
            return rootName;  // Root-level override
        }
    }
}
