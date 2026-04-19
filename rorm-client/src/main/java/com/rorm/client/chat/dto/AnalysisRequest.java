package com.rorm.client.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record AnalysisRequest(
    @NotBlank String query,
    @NotEmpty List<String> anchors
) {}
