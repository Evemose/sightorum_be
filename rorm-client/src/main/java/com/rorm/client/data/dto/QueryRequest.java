package com.rorm.client.data.dto;

import com.rorm.query.Query;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public record QueryRequest(
    @NotNull(message = "{validation.query.required}")
    @Valid
    Query query
) {}
