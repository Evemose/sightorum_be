package com.rorm.client.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SendMessageRequest(
    @NotBlank(message = "{validation.chat.message.text}")
    @Size(max = 100000, message = "{validation.size}")
    String message
) {}
