package com.rorm.viz.dto;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonAlias;

@JsonClassDescription("""
    Semantic color tokens for visualizations. Backend MUST emit a role,
    never a raw hex code. Frontend maps each role to the active theme's
    tailwind viz-* class.
    
    Roles:
    - base: primary series.
    - base-muted: secondary series / background context.
    - chrome: axes, gridlines, labels.
    - severity-amber: HIGH severity emphasis.
    - severity-muted: MED severity emphasis.
    - severity-low: LOW severity emphasis.
    - focal: highlight / emphasis.
    - divergent: contrasting series.
    - delta-positive: positive change indicator.
    - delta-negative: negative change indicator.
    - error: missing / unavailable data.""")
public enum ColorRole {
    @JsonAlias("base") BASE,
    @JsonAlias("base-muted") BASE_MUTED,
    @JsonAlias("chrome") CHROME,
    @JsonAlias("severity-amber") SEVERITY_AMBER,
    @JsonAlias("severity-muted") SEVERITY_MUTED,
    @JsonAlias("severity-low") SEVERITY_LOW,
    @JsonAlias("focal") FOCAL,
    @JsonAlias("divergent") DIVERGENT,
    @JsonAlias("delta-positive") DELTA_POSITIVE,
    @JsonAlias("delta-negative") DELTA_NEGATIVE,
    @JsonAlias("error") ERROR
}
