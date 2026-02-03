"""Linear Regression model trainer."""

import numpy as np
import polars as pl
from sklearn.linear_model import LinearRegression
from sklearn.metrics import mean_squared_error, mean_absolute_error, r2_score
from typing import Any, Optional

from ..base import ModelTrainer, ModelCategory


class LinearRegressionTrainer(ModelTrainer):
    """
    Ordinary Least Squares Linear Regression trainer.

    Fits a linear model to minimize residual sum of squares.
    """

    @property
    def model_type(self) -> str:
        return "linear_regression"

    @property
    def category(self) -> ModelCategory:
        return ModelCategory.REGRESSION

    @property
    def requires_target(self) -> bool:
        return True

    @property
    def default_params(self) -> dict[str, Any]:
        return {
            "fit_intercept": True,
        }

    @property
    def param_schema(self) -> dict[str, dict[str, Any]]:
        return {
            "fit_intercept": {
                "type": "bool",
                "required": False,
                "default": True,
                "description": "Whether to fit the intercept term",
            },
        }

    def train(
            self,
            X: pl.DataFrame,
            y: Optional[pl.Series],
            params: dict[str, Any],
    ) -> tuple[Any, dict[str, Any]]:
        """Train Linear Regression model."""
        X_np = X.to_numpy()
        y_np = y.to_numpy()

        model = LinearRegression(
            fit_intercept=params["fit_intercept"],
        )

        model.fit(X_np, y_np)
        predictions = model.predict(X_np)

        metrics: dict[str, Any] = {
            "r2_score": float(r2_score(y_np, predictions)),
            "mse": float(mean_squared_error(y_np, predictions)),
            "rmse": float(np.sqrt(mean_squared_error(y_np, predictions))),
            "mae": float(mean_absolute_error(y_np, predictions)),
        }

        if params["fit_intercept"]:
            metrics["intercept"] = float(model.intercept_)

        # Feature importances (coefficients)
        metrics["coefficients"] = {
            col: float(coef) for col, coef in zip(X.columns, model.coef_)
        }

        return model, metrics
