package com.rorm.viz.dto;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonClassDescription("""
    Top-level envelope for one rendered dashboard. A Digest is a sequence
    of Pages; each page is a scaffolded layout with narrative + chart
    slots. Exactly one Digest is returned per dashboard request.""")
public record Digest(

    @JsonPropertyDescription("Stable identifier for this digest, e.g. 'digest-2026-04-15'.")
    @JsonProperty(required = true)
    String id,

    @JsonPropertyDescription("Optional masthead title rendered above the pages.")
    @Nullable String title,

    @JsonPropertyDescription("Optional hero punchline shown above the pages.")
    @Nullable Punchline punchline,

    @JsonPropertyDescription("Ordered list of pages. Frontend renders them in the given order.")
    @JsonProperty(required = true)
    List<Page> pages,

    @JsonPropertyDescription("ISO-8601 timestamp of when this digest was generated.")
    @JsonProperty(required = true)
    Instant generatedAt
) {}
