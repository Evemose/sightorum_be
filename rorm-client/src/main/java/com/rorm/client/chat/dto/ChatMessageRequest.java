package com.rorm.client.chat.dto;

import jakarta.validation.constraints.NotBlank;
import org.jspecify.annotations.Nullable;

public record ChatMessageRequest(
    @NotBlank String message,
    @Nullable String sessionId
) {}
