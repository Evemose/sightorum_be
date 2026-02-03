package com.rorm.client.chat.dto;

import java.util.List;
import java.util.UUID;

public record ChatTreeResponse(
    UUID rootSessionId,
    ChatBranchDTO mainBranch,
    List<ChatBranchDTO> forks
) {}
