package com.rorm.viz.dto;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.jspecify.annotations.Nullable;

@JsonClassDescription("Masthead of a page: a required title and an optional short all-caps tag chip (e.g. 'TREND', 'RISK').")
public record PageHeader(

    @JsonPropertyDescription("Page title, shown prominently at the top of the page.")
    @JsonProperty(required = true)
    String title,

    @JsonPropertyDescription("Optional short all-caps chip, e.g. 'TREND', 'RISK'. Omit for plain headers.")
    @Nullable String tag
) {}
