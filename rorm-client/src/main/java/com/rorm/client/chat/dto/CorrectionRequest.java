package com.rorm.client.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Request to send a correction message at a specific point in the conversation.
 * All nodes at and after the specified node will be deleted before inserting the correction.
 */
public record CorrectionRequest(
    @NotNull(message = "{validation.chat.correction.beforeNodeId}")
    UUID beforeNodeId,

    @NotBlank(message = "{validation.chat.correction.message}")
    @Size(max = 100000, message = "{validation.size}")
    String message
) {}
