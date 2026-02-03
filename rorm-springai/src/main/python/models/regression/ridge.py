"""Ridge Regression model trainer."""

import numpy as np
import polars as pl
from sklearn.linear_model import Ridge
from sklearn.metrics import mean_squared_error, mean_absolute_error, r2_score
from typing import Any, Optional

from ..base import ModelTrainer, ModelCategory


class RidgeRegressionTrainer(ModelTrainer):
    """
    Ridge (L2 regularized) Regression trainer.

    Linear regression with L2 regularization to prevent overfitting.
    """

    @property
    def model_type(self) -> str:
        return "ridge_regression"

    @property
    def category(self) -> ModelCategory:
        return ModelCategory.REGRESSION

    @property
    def requires_target(self) -> bool:
        return True

    @property
    def default_params(self) -> dict[str, Any]:
        return {
            "alpha": 1.0,
            "fit_intercept": True,
            "max_iter": 1000,
            "solver": "auto",
        }

    @property
    def param_schema(self) -> dict[str, dict[str, Any]]:
        return {
            "alpha": {
                "type": "float",
                "required": False,
                "default": 1.0,
                "min": 0,
                "description": "Regularization strength (higher = more regularization)",
            },
            "fit_intercept": {
                "type": "bool",
                "required": False,
                "default": True,
                "description": "Whether to fit the intercept term",
            },
            "max_iter": {
                "type": "int",
                "required": False,
                "default": 1000,
                "min": 1,
                "description": "Maximum iterations for solver",
            },
            "solver": {
                "type": "str",
                "required": False,
                "default": "auto",
                "choices": ["auto", "svd", "cholesky", "lsqr", "sparse_cg", "sag", "saga"],
                "description": "Solver algorithm",
            },
        }

    def train(
            self,
            X: pl.DataFrame,
            y: Optional[pl.Series],
            params: dict[str, Any],
    ) -> tuple[Any, dict[str, Any]]:
        """Train Ridge Regression model."""
        X_np = X.to_numpy()
        y_np = y.to_numpy()

        model = Ridge(
            alpha=params["alpha"],
            fit_intercept=params["fit_intercept"],
            max_iter=params["max_iter"],
            solver=params["solver"],
        )

        model.fit(X_np, y_np)
        predictions = model.predict(X_np)

        metrics: dict[str, Any] = {
            "r2_score": float(r2_score(y_np, predictions)),
            "mse": float(mean_squared_error(y_np, predictions)),
            "rmse": float(np.sqrt(mean_squared_error(y_np, predictions))),
            "mae": float(mean_absolute_error(y_np, predictions)),
            "alpha": float(params["alpha"]),
        }

        if params["fit_intercept"]:
            metrics["intercept"] = float(model.intercept_)

        # Feature importances (coefficients)
        metrics["coefficients"] = {
            col: float(coef) for col, coef in zip(X.columns, model.coef_)
        }

        return model, metrics
