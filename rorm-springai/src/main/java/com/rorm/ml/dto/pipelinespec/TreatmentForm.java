package com.rorm.ml.dto.pipelinespec;

import com.fasterxml.jackson.annotation.JsonClassDescription;

@JsonClassDescription("""
    Treatment operationalization. CONTINUOUS uses dose-response estimation;
    BINARY_THRESHOLD uses a numeric threshold to binarize; CATEGORICAL uses
    label-encoded level contrasts.""")
public enum TreatmentForm {
    CONTINUOUS,
    BINARY_THRESHOLD,
    CATEGORICAL
}
