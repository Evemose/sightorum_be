package com.rorm.client.import_.dto;

import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.Map;

public record DetectedSchemaResponse(
    Map<String, DetectedRootDTO> roots
) {
    @JsonTypeInfo(
        use = JsonTypeInfo.Id.SIMPLE_NAME,
        property = "type"
    )
    public sealed interface DetectedAttributeDTO permits
        DetectedAttributeDTO.Basic,
        DetectedAttributeDTO.Collection,
        DetectedAttributeDTO.SingularReference,
        DetectedAttributeDTO.PluralReference,
        DetectedAttributeDTO.Composite,
        DetectedAttributeDTO.OneToOne {

        String name();

        record Basic(
            String name,
            String sourceColumn,
            SimpleDataType dataType
        ) implements DetectedAttributeDTO {}

        record Collection(
            String name,
            String sourceColumn,
            SimpleDataType elementType,
            String separator
        ) implements DetectedAttributeDTO {}

        record SingularReference(
            String name,
            String sourceColumn,
            String targetRootName,
            SimpleDataType dataType
        ) implements DetectedAttributeDTO {}

        record PluralReference(
            String name,
            String sourceColumn,
            String targetRootName,
            SimpleDataType dataType
        ) implements DetectedAttributeDTO {}

        record Composite(
            String name,
            Map<String, DetectedAttributeDTO> subAttributes
        ) implements DetectedAttributeDTO {}

        record OneToOne(
            String name,
            String targetRootName,
            Map<String, DetectedAttributeDTO> subAttributes
        ) implements DetectedAttributeDTO {}
    }

    public record DetectedRootDTO(
        String name,
        String sourceDataSource,
        Map<String, DetectedAttributeDTO> attributes,
        DetectedIdColumnDTO idColumn
    ) {}

    public record DetectedIdColumnDTO(
        String attributeName,
        String columnName,
        SimpleDataType dataType
    ) {}
}
