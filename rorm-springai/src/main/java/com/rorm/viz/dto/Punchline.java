package com.rorm.viz.dto;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

@JsonClassDescription("""
    A single strong statement shown above the pages of a digest, or as a
    page-level emphatic line. Plain text; no markdown emphasis markers.""")
public record Punchline(

    @JsonPropertyDescription("One-sentence declarative statement, plain text, no `**...**` emphasis markers.")
    @JsonProperty(required = true)
    String text
) {}
