package com.rorm.viz.dto;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

@JsonClassDescription("""
    Diagnostic rule result rendered in a page's fired-checks list. The
    frontend sorts incoming checks by severity (HIGH -> MED -> LOW); the
    backend may emit them in any order.""")
public record FiredCheck(

    @JsonPropertyDescription("""
        Stable rule identifier, uppercase snake-case, prefix 'CHK_'.
        Example: 'CHK_REV_DROP_3SIGMA'. Must be stable across releases.""")
    @JsonProperty(required = true)
    String code,

    @JsonPropertyDescription("Severity classification. Drives sort order and badge color on the frontend.")
    @JsonProperty(required = true)
    Severity severity,

    @JsonPropertyDescription("Human-readable explanation of what tripped the check. One or two sentences.")
    @JsonProperty(required = true)
    String text
) {}
