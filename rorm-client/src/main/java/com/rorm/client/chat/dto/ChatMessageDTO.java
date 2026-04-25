package com.rorm.client.chat.dto;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;

public record ChatMessageDTO(
    String role,
    @Nullable String text,
    @Nullable Map<String, Object> metadata,
    @Nullable List<ToolCall> toolCalls,
    @Nullable List<ToolResponse> toolResponses
) {
    public record ToolCall(String id, String name, String arguments) {}

    public record ToolResponse(String id, String name, String responseData) {}
}
