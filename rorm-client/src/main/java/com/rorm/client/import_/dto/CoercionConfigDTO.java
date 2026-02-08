package com.rorm.client.import_.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Configuration for applying coercion strategies to specific attributes during import.
 * Clean, immutable, no nulls.
 */
public record CoercionConfigDTO(
    @NotBlank(message = "Root name is required")
    String rootName,

    @NotBlank(message = "Attribute path is required")
    String attributePath,

    @NotNull(message = "Coercion strategy is required")
    @Valid
    CoercionStrategyDTO strategy
) {
}
