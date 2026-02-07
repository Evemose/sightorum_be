package com.rorm.client.import_.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeInfo.Id;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * DTO for hierarchical data import overrides.
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
public sealed interface HierarchicalOverrideDTO extends DetectionOverrideDTO permits
    HierarchicalOverrideDTO.DataTypeOverride,
    HierarchicalOverrideDTO.ForceComposite,
    HierarchicalOverrideDTO.ForceSeparateRoot,
    HierarchicalOverrideDTO.ForceBasic,
    HierarchicalOverrideDTO.ForceReference,
    HierarchicalOverrideDTO.IdOverride {

    /**
     * Path to the field being overridden.
     * Uses dot notation for nested fields: "address.zipcode", "items.price"
     */
    String fieldPath();

    /**
     * Strategy for determining the ID of a nested object when forcing it to be a separate root.
     */
    @JsonTypeInfo(
        use = Id.NAME,
        property = "type"
    )
    @JsonSubTypes({
        @JsonSubTypes.Type(value = IdStrategyDTO.UseField.class, name = "UseField"),
        @JsonSubTypes.Type(value = IdStrategyDTO.AutoGenerate.class, name = "AutoGenerate")
    })
    sealed interface IdStrategyDTO permits IdStrategyDTO.UseField, IdStrategyDTO.AutoGenerate {

        /**
         * Use an existing field from the nested object as the ID.
         */
        record UseField(
            @NotBlank(message = "{validation.hierarchical.idStrategy.fieldName}")
            String fieldName,

            SimpleDataType dataType
        ) implements IdStrategyDTO {}

        /**
         * Auto-generate a synthetic numeric ID.
         */
        record AutoGenerate() implements IdStrategyDTO {}
    }

    /**
     * Override the detected data type for a scalar field.
     */
    record DataTypeOverride(
        @NotBlank(message = "{validation.hierarchical.fieldPath}")
        String fieldPath,

        @NotNull(message = "{validation.hierarchical.dataType}")
        SimpleDataType dataType
    ) implements HierarchicalOverrideDTO {}

    /**
     * Force a nested object (or array of objects) to be treated as embedded composite.
     * <p>
     * Use when nested objects have an "id" field but should still be embedded
     * as value objects rather than creating a separate root.
     */
    record ForceComposite(
        @NotBlank(message = "{validation.hierarchical.fieldPath}")
        String fieldPath
    ) implements HierarchicalOverrideDTO {}

    /**
     * Force a nested object (or array of objects) to be treated as a separate root.
     * <p>
     * Use when nested objects don't have an "id" field but should be extracted
     * as a separate entity with a relationship.
     */
    record ForceSeparateRoot(
        @NotBlank(message = "{validation.hierarchical.fieldPath}")
        String fieldPath,

        @NotNull(message = "{validation.hierarchical.idStrategy}")
        @Valid
        IdStrategyDTO idStrategy
    ) implements HierarchicalOverrideDTO {}

    /**
     * Force a nested object or reference to be treated as a basic/scalar attribute.
     */
    record ForceBasic(
        @NotBlank(message = "{validation.hierarchical.fieldPath}")
        String fieldPath,
        @NotNull(message = "{validation.hierarchical.dataType}")
        SimpleDataType dataType
    ) implements HierarchicalOverrideDTO {}

    /**
     * Force a scalar field to be treated as a reference to another root.
     */
    record ForceReference(
        @NotBlank(message = "{validation.hierarchical.fieldPath}")
        String fieldPath,

        @NotBlank(message = "{validation.hierarchical.targetRootName}")
        String targetRootName
    ) implements HierarchicalOverrideDTO {}

    /**
     * Override the ID column configuration for a root.
     */
    record IdOverride(
        @NotBlank(message = "{validation.hierarchical.rootName}")
        String rootName,

        String fieldName,

        SimpleDataType dataType
    ) implements HierarchicalOverrideDTO {
        @Override
        public String fieldPath() {
            return rootName;
        }
    }
}
