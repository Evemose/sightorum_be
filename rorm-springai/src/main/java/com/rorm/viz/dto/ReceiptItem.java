package com.rorm.viz.dto;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

@JsonClassDescription("""
    Provenance / audit chip. Label-value pair shown on a page to describe
    sample size, window, source, method, etc.
    The value is polymorphic: strings for labels like 'warehouse.x',
    numbers for sample counts. Emitted as `string | number`.""")
public record ReceiptItem(

    @JsonPropertyDescription("Short label, e.g. 'n', 'window', 'source', 'method'.")
    @JsonProperty(required = true)
    String label,

    @JsonPropertyDescription("""
        Value. Strings render as-is; numbers render with locale formatting.
        Use Number (or subclass) for numeric values, String otherwise.""")
    @JsonProperty(required = true)
    Object value
) {}
