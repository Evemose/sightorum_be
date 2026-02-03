package com.rorm.client.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CorrectionRequest(
    @NotNull(message = "{validation.chat.correction.type}")
    CorrectionType type,

    @NotBlank(message = "{validation.chat.correction.message}")
    @Size(max = 100000, message = "{validation.size}")
    String message
) {
    public enum CorrectionType {
        INTERRUPT,
        EDIT
    }
}
