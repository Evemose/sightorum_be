"""SARIMAX time series model trainer."""

import numpy as np
import polars as pl
from statsmodels.tsa.statespace.sarimax import SARIMAX
from typing import Any, Optional

from ..base import ModelTrainer, ModelCategory


class SARIMAXTrainer(ModelTrainer):
    """
    SARIMAX (Seasonal ARIMA with eXogenous variables) trainer.

    Extension of ARIMA that supports:
    - Seasonal patterns (daily, weekly, monthly, yearly)
    - Exogenous variables (external predictors)

    Best for time series with clear seasonal patterns and external factors.
    """

    @property
    def model_type(self) -> str:
        return "sarimax"

    @property
    def category(self) -> ModelCategory:
        return ModelCategory.TEMPORAL

    @property
    def requires_target(self) -> bool:
        return True

    @property
    def minimum_rows(self) -> int:
        return 50  # Needs more data for seasonal patterns

    @property
    def default_params(self) -> dict[str, Any]:
        return {
            # Non-seasonal orders
            "order_p": 1,
            "order_d": 1,
            "order_q": 1,
            # Seasonal orders
            "seasonal_p": 1,
            "seasonal_d": 1,
            "seasonal_q": 1,
            "seasonal_period": 12,  # Monthly seasonality by default
            # Model options
            "trend": "n",
            "enforce_stationarity": True,
            "enforce_invertibility": True,
            "forecast_steps": 10,
        }

    @property
    def param_schema(self) -> dict[str, dict[str, Any]]:
        return {
            "order_p": {
                "type": "int",
                "required": False,
                "default": 1,
                "min": 0,
                "max": 10,
                "description": "AR order for non-seasonal component",
            },
            "order_d": {
                "type": "int",
                "required": False,
                "default": 1,
                "min": 0,
                "max": 3,
                "description": "Differencing order for non-seasonal component",
            },
            "order_q": {
                "type": "int",
                "required": False,
                "default": 1,
                "min": 0,
                "max": 10,
                "description": "MA order for non-seasonal component",
            },
            "seasonal_p": {
                "type": "int",
                "required": False,
                "default": 1,
                "min": 0,
                "max": 5,
                "description": "AR order for seasonal component",
            },
            "seasonal_d": {
                "type": "int",
                "required": False,
                "default": 1,
                "min": 0,
                "max": 2,
                "description": "Differencing order for seasonal component",
            },
            "seasonal_q": {
                "type": "int",
                "required": False,
                "default": 1,
                "min": 0,
                "max": 5,
                "description": "MA order for seasonal component",
            },
            "seasonal_period": {
                "type": "int",
                "required": False,
                "default": 12,
                "min": 2,
                "max": 365,
                "description": "Seasonal period (e.g., 12=monthly, 7=weekly, 4=quarterly)",
            },
            "trend": {
                "type": "str",
                "required": False,
                "default": "n",
                "choices": ["n", "c", "t", "ct"],
                "description": "Trend: n=none, c=constant, t=linear, ct=both",
            },
            "enforce_stationarity": {
                "type": "bool",
                "required": False,
                "default": True,
                "description": "Transform parameters to enforce stationarity",
            },
            "enforce_invertibility": {
                "type": "bool",
                "required": False,
                "default": True,
                "description": "Transform parameters to enforce invertibility",
            },
            "forecast_steps": {
                "type": "int",
                "required": False,
                "default": 10,
                "min": 1,
                "max": 365,
                "description": "Number of steps to forecast for metrics",
            },
        }

    def train(
            self,
            X: pl.DataFrame,
            y: Optional[pl.Series],
            params: dict[str, Any],
    ) -> tuple[Any, dict[str, Any]]:
        """Train SARIMAX model."""
        y_np = y.to_numpy().astype(float)

        # Check for exogenous variables
        exog = None
        if X.width > 0:
            exog = X.to_numpy()

        order = (params["order_p"], params["order_d"], params["order_q"])
        seasonal_order = (
            params["seasonal_p"],
            params["seasonal_d"],
            params["seasonal_q"],
            params["seasonal_period"],
        )

        model = SARIMAX(
            endog=y_np,
            exog=exog,
            order=order,
            seasonal_order=seasonal_order,
            trend=params["trend"],
            enforce_stationarity=params["enforce_stationarity"],
            enforce_invertibility=params["enforce_invertibility"],
        )

        fitted = model.fit(disp=False)

        # Compute metrics
        residuals = fitted.resid
        forecast_steps = params["forecast_steps"]

        metrics: dict[str, Any] = {
            "order": {"p": order[0], "d": order[1], "q": order[2]},
            "seasonal_order": {
                "P": seasonal_order[0],
                "D": seasonal_order[1],
                "Q": seasonal_order[2],
                "s": seasonal_order[3],
            },
            "aic": float(fitted.aic),
            "bic": float(fitted.bic),
            "hqic": float(fitted.hqic),
            "llf": float(fitted.llf),
            "n_observations": int(fitted.nobs),
        }

        # Residual statistics
        metrics["residuals"] = {
            "mean": float(np.mean(residuals)),
            "std": float(np.std(residuals)),
            "min": float(np.min(residuals)),
            "max": float(np.max(residuals)),
        }

        # Model parameters
        params_summary = {}
        for name, value in fitted.params.items():
            params_summary[name] = float(value)
        metrics["fitted_parameters"] = params_summary

        # In-sample fit metrics
        predictions = fitted.fittedvalues
        if len(predictions) > 0:
            valid_idx = ~np.isnan(predictions) & ~np.isnan(y_np[: len(predictions)])
            if np.sum(valid_idx) > 0:
                mse = np.mean(
                    (y_np[: len(predictions)][valid_idx] - predictions[valid_idx]) ** 2
                )
                metrics["mse"] = float(mse)
                metrics["rmse"] = float(np.sqrt(mse))

        # Forecast preview
        try:
            forecast = fitted.forecast(steps=min(forecast_steps, 30))
            metrics["forecast_preview"] = [float(f) for f in forecast[:10]]
        except Exception:
            pass

        # Has exogenous variables
        metrics["has_exogenous"] = exog is not None
        if exog is not None:
            metrics["n_exogenous_features"] = exog.shape[1]

        return fitted, metrics
