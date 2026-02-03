"""ARIMA time series model trainer."""

import numpy as np
import polars as pl
from statsmodels.tsa.arima.model import ARIMA
from typing import Any, Optional

from ..base import ModelTrainer, ModelCategory


class ARIMATrainer(ModelTrainer):
    """
    ARIMA (AutoRegressive Integrated Moving Average) trainer.

    Time series forecasting model combining:
    - AR (AutoRegressive): dependence on lagged values
    - I (Integrated): differencing for stationarity
    - MA (Moving Average): dependence on lagged forecast errors

    Note: ARIMA expects a single time series column as the target.
    Feature columns can include exogenous variables.
    """

    @property
    def model_type(self) -> str:
        return "arima"

    @property
    def category(self) -> ModelCategory:
        return ModelCategory.TEMPORAL

    @property
    def requires_target(self) -> bool:
        return True

    @property
    def minimum_rows(self) -> int:
        return 30  # Time series needs sufficient history

    @property
    def default_params(self) -> dict[str, Any]:
        return {
            "order_p": 1,  # AR order
            "order_d": 1,  # Differencing order
            "order_q": 1,  # MA order
            "trend": "n",  # No trend by default
            "enforce_stationarity": True,
            "enforce_invertibility": True,
            "concentrate_scale": False,
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
                "description": "AR (autoregressive) order - number of lagged observations",
            },
            "order_d": {
                "type": "int",
                "required": False,
                "default": 1,
                "min": 0,
                "max": 3,
                "description": "I (integrated) order - degree of differencing",
            },
            "order_q": {
                "type": "int",
                "required": False,
                "default": 1,
                "min": 0,
                "max": 10,
                "description": "MA (moving average) order - size of moving average window",
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
                "description": "Transform AR parameters to enforce stationarity",
            },
            "enforce_invertibility": {
                "type": "bool",
                "required": False,
                "default": True,
                "description": "Transform MA parameters to enforce invertibility",
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
        """Train ARIMA model."""
        y_np = y.to_numpy().astype(float)

        # Check for exogenous variables
        exog = None
        if X.width > 0:
            exog = X.to_numpy()

        order = (params["order_p"], params["order_d"], params["order_q"])

        model = ARIMA(
            endog=y_np,
            exog=exog,
            order=order,
            trend=params["trend"],
            enforce_stationarity=params["enforce_stationarity"],
            enforce_invertibility=params["enforce_invertibility"],
            concentrate_scale=params.get("concentrate_scale", False),
        )

        fitted = model.fit()

        # Compute metrics
        residuals = fitted.resid
        forecast_steps = params["forecast_steps"]

        metrics: dict[str, Any] = {
            "order": {"p": order[0], "d": order[1], "q": order[2]},
            "aic": float(fitted.aic() if callable(fitted.aic) else fitted.aic),
            "bic": float(fitted.bic() if callable(fitted.bic) else fitted.bic),
            "hqic": float(fitted.hqic() if callable(fitted.hqic) else fitted.hqic),
            "llf": float(fitted.llf() if callable(fitted.llf) else fitted.llf),  # Log-likelihood
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
        if hasattr(fitted, "arparams"):
            arparams = fitted.arparams() if callable(fitted.arparams) else fitted.arparams
            if arparams is not None:
                metrics["ar_coefficients"] = [float(p) for p in arparams]
        if hasattr(fitted, "maparams"):
            maparams = fitted.maparams() if callable(fitted.maparams) else fitted.maparams
            if maparams is not None:
                metrics["ma_coefficients"] = [float(p) for p in maparams]

        # In-sample fit metrics
        predictions = fitted.fittedvalues() if callable(fitted.fittedvalues) else fitted.fittedvalues
        if len(predictions) > 0:
            mse = np.mean((y_np[len(y_np) - len(predictions):] - predictions) ** 2)
            metrics["mse"] = float(mse)
            metrics["rmse"] = float(np.sqrt(mse))

        # Forecast preview
        try:
            forecast = fitted.forecast(steps=min(forecast_steps, 30))
            metrics["forecast_preview"] = [float(f) for f in forecast[:10]]
        except Exception:
            pass

        return fitted, metrics
