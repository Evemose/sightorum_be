package com.rorm.viz.dto;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

@JsonClassDescription("Highlighted advisory box attached to a page, styled by severity.")
public record CalloutBox(

    @JsonPropertyDescription("Visual emphasis of the callout. HIGH -> amber, MED -> muted, LOW -> low-contrast.")
    @JsonProperty(required = true)
    Severity severity,

    @JsonPropertyDescription("Callout body. Plain text, one sentence to one short paragraph.")
    @JsonProperty(required = true)
    String text
) {}
