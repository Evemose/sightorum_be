package com.rorm.client.chat.dto;

import jakarta.validation.constraints.NotBlank;
import org.jspecify.annotations.Nullable;

import java.util.List;

public record AnalysisRequest(
    @NotBlank String query,
    @Nullable List<String> anchors
) {}
