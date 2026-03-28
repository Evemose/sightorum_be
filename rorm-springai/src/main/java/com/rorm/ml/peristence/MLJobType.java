package com.rorm.ml.peristence;

import com.rorm.ml.dto.*;

public enum MLJobType {

    TRAINING(TrainingJobRequest.class),
    TUNING(TuningJobRequest.class),
    STABILITY_SELECTION(StabilitySelectionJobRequest.class),
    SHAP(ShapJobRequest.class),
    CAUSAL_VERIFICATION(CausalVerificationJobRequest.class);

    private final Class<? extends AsyncJobRequest> requestType;

    MLJobType(Class<? extends AsyncJobRequest> requestType) {
        this.requestType = requestType;
    }

    public Class<? extends AsyncJobRequest> requestType() {
        return requestType;
    }
}
