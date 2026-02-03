package com.rorm.client.chat.dto;

import com.rorm.client.validation.ValidSchemaName;
import jakarta.validation.constraints.NotBlank;

public record CreateSessionRequest(
    @NotBlank(message = "{validation.chat.session.schemaName}")
    @ValidSchemaName
    String schemaName
) {}
