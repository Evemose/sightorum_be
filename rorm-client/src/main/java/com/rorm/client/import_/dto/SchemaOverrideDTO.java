package com.rorm.client.import_.dto;

import com.rorm.client.validation.ValidSchemaName;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Objects;

public sealed interface SchemaOverrideDTO extends DetectionOverrideDTO permits
    SchemaOverrideDTO.Basic,
    SchemaOverrideDTO.Collection,
    SchemaOverrideDTO.Id,
    SchemaOverrideDTO.Composite,
    SchemaOverrideDTO.OneToOneRoot,
    SchemaOverrideDTO.SingularReference,
    SchemaOverrideDTO.PluralReference {

    String attributeName();

    record Basic(
        @NotBlank(message = "{validation.override.attributeName}")
        String attributeName,

        @NotNull(message = "{validation.override.dataType}")
        SimpleDataType dataType
    ) implements SchemaOverrideDTO {}

    record Collection(
        @NotBlank(message = "{validation.override.attributeName}")
        String attributeName,

        @NotBlank(message = "{validation.override.separator}")
        @Size(min = 1, max = 10, message = "{validation.size}")
        String separator,

        @NotNull(message = "{validation.override.dataType}")
        SimpleDataType elementType
    ) implements SchemaOverrideDTO {}

    record Id(
        @NotBlank(message = "{validation.override.attributeName}")
        String attributeName,

        @NotBlank(message = "{validation.override.columnName}")
        String columnName,

        SimpleDataType dataType
    ) implements SchemaOverrideDTO {}

    record Composite(
        @NotBlank(message = "{validation.override.attributeName}")
        String attributeName,

        @NotEmpty(message = "{validation.override.subAttributeColumns}")
        List<@NotBlank(message = "{validation.override.columnName}") String> subAttributeColumns,

        @Valid
        List<SchemaOverrideDTO> nestedOverrides
    ) implements SchemaOverrideDTO {
        public Composite {
            subAttributeColumns = List.copyOf(Objects.requireNonNullElseGet(subAttributeColumns, List::of));
            nestedOverrides = List.copyOf(Objects.requireNonNullElseGet(nestedOverrides, List::of));
        }
    }

    record OneToOneRoot(
        @NotBlank(message = "{validation.override.attributeName}")
        String attributeName,

        @NotBlank(message = "{validation.override.targetRootName}")
        @ValidSchemaName
        String targetRootName,

        @NotEmpty(message = "{validation.override.subAttributeColumns}")
        List<@NotBlank(message = "{validation.override.columnName}") String> subAttributeColumns,

        String idColumn,

        @Valid
        List<SchemaOverrideDTO> nestedOverrides
    ) implements SchemaOverrideDTO {
        public OneToOneRoot {
            subAttributeColumns = List.copyOf(Objects.requireNonNullElseGet(subAttributeColumns, List::of));
            nestedOverrides = List.copyOf(Objects.requireNonNullElseGet(nestedOverrides, List::of));
        }
    }

    record SingularReference(
        @NotBlank(message = "{validation.override.attributeName}")
        String attributeName,

        @NotBlank(message = "{validation.override.targetRootName}")
        @ValidSchemaName
        String targetRootName
    ) implements SchemaOverrideDTO {}

    record PluralReference(
        @NotBlank(message = "{validation.override.attributeName}")
        String attributeName,

        @NotBlank(message = "{validation.override.targetRootName}")
        @ValidSchemaName
        String targetRootName
    ) implements SchemaOverrideDTO {}
}
