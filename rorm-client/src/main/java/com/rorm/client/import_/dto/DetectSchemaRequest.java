package com.rorm.client.import_.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.UUID;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record DetectSchemaRequest(
    @NotBlank(message = "{validation.import.uploadId}")
    @UUID(message = "{validation.import.uploadId.format}")
    String uploadId,

    @Size(min = 1, max = 10, message = "{validation.import.listSeparator.size}")
    String listSeparator,

    @Valid
    Map<String, List<@Valid DetectionOverrideDTO>> overridesByRoot
) {
    private static final String DEFAULT_LIST_SEPARATOR = ",";

    public DetectSchemaRequest {
        if (listSeparator == null || listSeparator.isBlank()) {
            listSeparator = DEFAULT_LIST_SEPARATOR;
        }
        overridesByRoot = Map.copyOf(Objects.requireNonNullElseGet(overridesByRoot, Map::of));
    }
}
