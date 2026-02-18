package com.rorm.client.research.dto;

import jakarta.validation.constraints.NotBlank;

public record StartResearchRequest(
    @NotBlank String schemaName,
    @NotBlank String query,
    boolean mock
) {}
