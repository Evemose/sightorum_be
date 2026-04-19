package com.rorm.viz.dto;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

@JsonClassDescription("""
    Text annotation attached to a chart's scaffold. The anchor is
    polymorphic (data-coordinate object OR corner-string), see
    AnnotationAnchor for the wire shapes.""")
public record AnnotationDef(

    @JsonPropertyDescription("Annotation text. One short line is recommended.")
    @JsonProperty(required = true)
    String text,

    @JsonPropertyDescription("""
        Anchor position. Either a `{x, y}` data-coordinate object, or one
        of the corner string literals ('top-left', 'top-right',
        'bottom-left', 'bottom-right').""")
    @JsonProperty(required = true)
    AnnotationAnchor anchor
) {}
