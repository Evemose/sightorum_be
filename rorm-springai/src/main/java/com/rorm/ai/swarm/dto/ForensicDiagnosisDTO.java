package com.rorm.ai.swarm.dto;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

@JsonClassDescription("Forensic pathologist's post-mortem diagnosis of pipeline results")
public record ForensicDiagnosisDTO(

    @JsonPropertyDescription("Classification of the null result (e.g. DOMINATED_MECHANISM, INSUFFICIENT_POWER, TRUE_NULL)")
    @JsonProperty(required = true)
    String classification,

    @JsonPropertyDescription("Narrative summary of the forensic analysis")
    @JsonProperty(required = true)
    String summary,

    @JsonPropertyDescription("Key evidence points supporting the diagnosis")
    @JsonProperty(required = true)
    List<String> evidencePoints
) {}
