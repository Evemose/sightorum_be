package com.rorm.client.chat.dto;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.UUID;

public record ChatBranchDTO(
    UUID sessionId,
    @Nullable UUID parentSessionId,
    String status,
    @Nullable ForkPoint forkPoint,
    List<ChatNodeDTO> nodes
) {
    public record ForkPoint(
        UUID afterNodeId,
        String reason
    ) {}
}
