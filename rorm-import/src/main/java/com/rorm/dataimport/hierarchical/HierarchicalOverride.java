package com.rorm.dataimport.hierarchical;

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
public sealed interface HierarchicalOverride permits
    HierarchicalOverride.DataTypeOverride,
    HierarchicalOverride.ForceComposite,
    HierarchicalOverride.ForceSeparateRoot {

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
}
