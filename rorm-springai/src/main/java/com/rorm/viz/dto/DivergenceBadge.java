package com.rorm.viz.dto;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.jspecify.annotations.Nullable;

@JsonClassDescription("""
    Inline badge summarising how one value diverges from a reference.
    Renders a small colored pill; the optional label overrides the
    default direction label.""")
public record DivergenceBadge(

    @JsonPropertyDescription("Divergence direction: 'high', 'low', or 'near' (no meaningful divergence).")
    @JsonProperty(required = true)
    DivergenceLevel direction,

    @JsonPropertyDescription("Optional text shown inside the badge. If omitted, the direction is rendered.")
    @Nullable String label
) {}
