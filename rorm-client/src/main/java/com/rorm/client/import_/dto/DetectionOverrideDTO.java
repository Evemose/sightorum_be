package com.rorm.client.import_.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeInfo.Id;

/**
 * Base interface for all detection override DTOs.
 * <p>
 * Mirrors the domain {@code DetectionOverride} sealed interface,
 * allowing both flat (schema) and hierarchical overrides to be
 * passed in a single unified map.
 */
@JsonTypeInfo(
    use = Id.NAME,
    property = "type"
)
@JsonSubTypes({
    // Flat/Schema overrides
    @JsonSubTypes.Type(value = SchemaOverrideDTO.Basic.class, name = "Basic"),
    @JsonSubTypes.Type(value = SchemaOverrideDTO.Collection.class, name = "Collection"),
    @JsonSubTypes.Type(value = SchemaOverrideDTO.Id.class, name = "Id"),
    @JsonSubTypes.Type(value = SchemaOverrideDTO.Composite.class, name = "Composite"),
    @JsonSubTypes.Type(value = SchemaOverrideDTO.OneToOneRoot.class, name = "OneToOneRoot"),
    @JsonSubTypes.Type(value = SchemaOverrideDTO.SingularReference.class, name = "SingularReference"),
    @JsonSubTypes.Type(value = SchemaOverrideDTO.PluralReference.class, name = "PluralReference"),
    // Hierarchical overrides
    @JsonSubTypes.Type(value = HierarchicalOverrideDTO.DataTypeOverride.class, name = "DataTypeOverride"),
    @JsonSubTypes.Type(value = HierarchicalOverrideDTO.ForceComposite.class, name = "ForceComposite"),
    @JsonSubTypes.Type(value = HierarchicalOverrideDTO.ForceSeparateRoot.class, name = "ForceSeparateRoot"),
    @JsonSubTypes.Type(value = HierarchicalOverrideDTO.ForceBasic.class, name = "ForceBasic"),
    @JsonSubTypes.Type(value = HierarchicalOverrideDTO.ForceReference.class, name = "ForceReference"),
    @JsonSubTypes.Type(value = HierarchicalOverrideDTO.IdOverride.class, name = "HierarchicalIdOverride")
})
public sealed interface DetectionOverrideDTO permits SchemaOverrideDTO, HierarchicalOverrideDTO {
}
