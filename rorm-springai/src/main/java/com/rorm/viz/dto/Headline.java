package com.rorm.viz.dto;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

@JsonClassDescription("""
    Short bold paragraph rendered at the top of a page.
    
    Emphasis:
    Highlighted spans (numbers, named entities) should be wrapped in
    `**...**` markers, e.g. 'Revenue rose **12.4%** vs. prior quarter'.
    The frontend renders the markers as emphasized tokens; unwrapped
    text renders plain.""")
public record Headline(

    @JsonPropertyDescription("""
        Headline text. Numeric / entity spans to emphasise should be wrapped
        in `**...**` markers; everything else renders plain.""")
    @JsonProperty(required = true)
    String text
) {}
