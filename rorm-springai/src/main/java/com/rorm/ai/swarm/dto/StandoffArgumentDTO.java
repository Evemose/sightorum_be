package com.rorm.ai.swarm.dto;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

@JsonClassDescription("""
    Free-form prose argument from one side of the advocate/prosecutor
    standoff. The argument itself is the full raw text; the other fields
    extract a handful of cues for downstream weighing by the judge.""")
public record StandoffArgumentDTO(

    @JsonPropertyDescription("Which side produced the argument: ADVOCATE or PROSECUTOR.")
    @JsonProperty(required = true)
    String role,

    @JsonPropertyDescription("The full argument text as produced, verbatim.")
    @JsonProperty(required = true)
    String argument,

    @JsonPropertyDescription("""
        Very short (one sentence) headline of the side's bottom line — the
        single sentence that best summarizes where the argument lands.""")
    @JsonProperty(required = true)
    String headline,

    @JsonPropertyDescription("""
        Explicit concessions the side made to the other side, or the empty
        string when no concessions were made. Helps the judge see which
        points are already agreed.""")
    @JsonProperty(required = true)
    String concessions
) {}
