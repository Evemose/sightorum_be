package com.rorm.client.import_.dto;

import com.rorm.client.validation.ValidSchemaName;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.hibernate.validator.constraints.UUID;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record StartImportRequest(
    @NotBlank(message = "{validation.import.uploadId}")
    @UUID(message = "{validation.import.uploadId.format}")
    String uploadId,

    @NotBlank(message = "{validation.import.targetSchema}")
    @ValidSchemaName
    String targetSchema,

    @Min(value = 100, message = "{validation.import.chunkSize.min}")
    @Max(value = 100000, message = "{validation.import.chunkSize.max}")
    int chunkSize,

    @Valid
    Map<String, List<@Valid DetectionOverrideDTO>> overridesByRoot,

    @Valid
    List<@Valid CoercionConfigDTO> coercionConfigs
) {

    public StartImportRequest {
        overridesByRoot = Map.copyOf(Objects.requireNonNullElseGet(overridesByRoot, Map::of));
        coercionConfigs = List.copyOf(Objects.requireNonNullElseGet(coercionConfigs, List::of));
    }
}
