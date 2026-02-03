package com.rorm.ml.dto;

/**
 * Float parameter space for hyperparameter tuning.
 *
 * @param low  Lower bound (inclusive)
 * @param high Upper bound (inclusive)
 * @param log  Use logarithmic scale (default: false)
 */
public record FloatSpace(
    Double low,
    Double high,
    Boolean log
) implements ParamSpace {

    public FloatSpace(Double low, Double high) {
        this(low, high, false);
    }

    public FloatSpace {
        if (log == null) {
            log = false;
        }
    }

    @Override
    public String type() {
        return log ? "loguniform" : "float";
    }
}
