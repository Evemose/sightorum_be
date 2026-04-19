package com.rorm.viz.dto;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

@JsonClassDescription("Small footnote rendered directly under the primary chart of a page.")
public record ChartCaption(

    @JsonPropertyDescription("Plain-text caption, one or two sentences. No markdown.")
    @JsonProperty(required = true)
    String text
) {}
