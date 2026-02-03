package com.rorm.ml.dto;

/**
 * Integer parameter space for hyperparameter tuning.
 *
 * @param low  Lower bound (inclusive)
 * @param high Upper bound (inclusive)
 * @param log  Use logarithmic scale (default: false)
 */
public record IntSpace(
    Integer low,
    Integer high,
    Boolean log
) implements ParamSpace {

    public IntSpace(Integer low, Integer high) {
        this(low, high, false);
    }

    public IntSpace {
        if (log == null) {
            log = false;
        }
    }

    @Override
    public String type() {
        return "int";
    }
}
