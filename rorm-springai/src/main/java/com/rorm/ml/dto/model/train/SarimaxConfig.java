package com.rorm.ml.dto.model.train;

import com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.rorm.ml.dto.ModelConfig;

/**
 * Configuration for SARIMAX (Seasonal ARIMA with eXogenous variables).
 * Time series forecasting with seasonal patterns and external predictors.
 *
 * @param orderP               Non-seasonal AR order (0-10, default: 1)
 * @param orderD               Non-seasonal differencing order (0-3, default: 1)
 * @param orderQ               Non-seasonal MA order (0-10, default: 1)
 * @param seasonalP            Seasonal AR order (0-5, default: 1)
 * @param seasonalD            Seasonal differencing order (0-2, default: 1)
 * @param seasonalQ            Seasonal MA order (0-5, default: 1)
 * @param seasonalPeriod       Seasonal period: 12=monthly, 7=weekly, 4=quarterly (2-365, default: 12)
 * @param trend                Trend: "n" (none), "c" (constant), "t" (linear), "ct" (both) (default: "n")
 * @param enforceStationarity  Transform parameters to enforce stationarity (default: true)
 * @param enforceInvertibility Transform parameters to enforce invertibility (default: true)
 * @param forecastSteps        Number of steps to forecast for metrics (1-365, default: 10)
 */
@JsonNaming(SnakeCaseStrategy.class)
public record SarimaxConfig(Integer orderP, Integer orderD, Integer orderQ, Integer seasonalP, Integer seasonalD,
                            Integer seasonalQ, Integer seasonalPeriod, String trend, Boolean enforceStationarity,
                            Boolean enforceInvertibility, Integer forecastSteps
) implements ModelConfig {

    public SarimaxConfig {
        if (orderP == null) {
            orderP = 1;
        }
        if (orderD == null) {
            orderD = 1;
        }
        if (orderQ == null) {
            orderQ = 1;
        }
        if (seasonalP == null) {
            seasonalP = 1;
        }
        if (seasonalD == null) {
            seasonalD = 1;
        }
        if (seasonalQ == null) {
            seasonalQ = 1;
        }
        if (seasonalPeriod == null) {
            seasonalPeriod = 12;
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
        return "sarimax";
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
