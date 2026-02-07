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
    /**
     * Helper factory method for common use case: single attribute coercion.
     */
    public static CoercionConfigDTO of(String rootName, String attributePath, CoercionStrategyDTO strategy) {
        return new CoercionConfigDTO(rootName, attributePath, strategy);
    }

    /**
     * Helper factory method for skip strategy.
     */
    public static CoercionConfigDTO skip(String rootName, String attributePath) {
        return new CoercionConfigDTO(rootName, attributePath, new CoercionStrategyDTO.SkipDTO());
    }

    /**
     * Helper factory method for forward fill strategy.
     */
    public static CoercionConfigDTO forwardFill(String rootName, String attributePath) {
        return new CoercionConfigDTO(rootName, attributePath, new CoercionStrategyDTO.ForwardFillDTO());
    }

    /**
     * Helper factory method for use mean strategy.
     */
    public static CoercionConfigDTO useMean(String rootName, String attributePath) {
        return new CoercionConfigDTO(rootName, attributePath, new CoercionStrategyDTO.UseMeanDTO());
    }
}
