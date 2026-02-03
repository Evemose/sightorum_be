package com.rorm.ml.dto.model.train;

import com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * Configuration for ARIMA (AutoRegressive Integrated Moving Average).
 * Time series forecasting model.
 *
 * @param orderP               AR (autoregressive) order (0-10, default: 1)
 * @param orderD               Differencing order for stationarity (0-3, default: 1)
 * @param orderQ               MA (moving average) order (0-10, default: 1)
 * @param trend                Trend component: "n" (none), "c" (constant), "t" (linear), "ct" (both) (default: "n")
 * @param enforceStationarity  Transform parameters to enforce stationarity (default: true)
 * @param enforceInvertibility Transform parameters to enforce invertibility (default: true)
 * @param forecastSteps        Number of steps to forecast for metrics (1-365, default: 10)
 */
@JsonNaming(SnakeCaseStrategy.class)
public record ArimaConfig(Integer orderP, Integer orderD, Integer orderQ, String trend, Boolean enforceStationarity,
                          Boolean enforceInvertibility, Integer forecastSteps
) implements ModelConfig {

    public ArimaConfig {
        if (orderP == null) {
            orderP = 1;
        }
        if (orderD == null) {
            orderD = 1;
        }
        if (orderQ == null) {
            orderQ = 1;
        }
        if (trend == null) {
            trend = "n";
        }
        if (enforceStationarity == null) {
            enforceStationarity = true;
        }
        if (enforceInvertibility == null) {
            enforceInvertibility = true;
        }
        if (forecastSteps == null) {
            forecastSteps = 10;
        }
    }

    @Override
    public String modelType() {
        return "arima";
    }

    @Override
    public boolean requiresTarget() {
        return true;
    }

    @Override
    public ModelCategory category() {
        return ModelCategory.TEMPORAL;
    }
}
