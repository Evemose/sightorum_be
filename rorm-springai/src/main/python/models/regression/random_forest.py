"""Random Forest Regressor model trainer."""

import numpy as np
import polars as pl
from sklearn.ensemble import RandomForestRegressor
from sklearn.metrics import mean_squared_error, mean_absolute_error, r2_score
from typing import Any, Optional

from ..base import ModelTrainer, ModelCategory


class RandomForestRegressorTrainer(ModelTrainer):
    """
    Random Forest Regressor trainer.

    Ensemble of decision trees with bagging for regression.
    """

    @property
    def model_type(self) -> str:
        return "random_forest_regressor"

    @property
    def category(self) -> ModelCategory:
        return ModelCategory.REGRESSION

    @property
    def requires_target(self) -> bool:
        return True

    @property
    def default_params(self) -> dict[str, Any]:
        return {
            "n_estimators": 100,
            "max_depth": None,
            "min_samples_split": 2,
            "min_samples_leaf": 1,
            "max_features": "sqrt",
            "random_state": 42,
            "n_jobs": -1,
        }

    @property
    def param_schema(self) -> dict[str, dict[str, Any]]:
        return {
            "n_estimators": {
                "type": "int",
                "required": False,
                "default": 100,
                "min": 1,
                "max": 10000,
                "description": "Number of trees in the forest",
            },
            "max_depth": {
                "type": "int",
                "required": False,
                "default": None,
                "min": 1,
                "description": "Maximum depth of trees (None = unlimited)",
            },
            "min_samples_split": {
                "type": "int",
                "required": False,
                "default": 2,
                "min": 2,
                "description": "Minimum samples required to split a node",
            },
            "min_samples_leaf": {
                "type": "int",
                "required": False,
                "default": 1,
                "min": 1,
                "description": "Minimum samples required at a leaf node",
            },
            "max_features": {
                "type": "str",
                "required": False,
                "default": "sqrt",
                "choices": ["sqrt", "log2", "auto"],
                "description": "Number of features to consider for best split",
            },
            "random_state": {
                "type": "int",
                "required": False,
                "default": 42,
                "description": "Random seed for reproducibility",
            },
            "n_jobs": {
                "type": "int",
                "required": False,
                "default": -1,
                "description": "Number of parallel jobs (-1 = all cores)",
            },
        }

    def train(
            self,
            X: pl.DataFrame,
            y: Optional[pl.Series],
            params: dict[str, Any],
    ) -> tuple[Any, dict[str, Any]]:
        """Train Random Forest Regressor model."""
        X_np = X.to_numpy()
        y_np = y.to_numpy()

        model = RandomForestRegressor(
            n_estimators=params["n_estimators"],
            max_depth=params.get("max_depth"),
            min_samples_split=params["min_samples_split"],
            min_samples_leaf=params["min_samples_leaf"],
            max_features=params["max_features"],
            random_state=params.get("random_state"),
            n_jobs=params["n_jobs"],
        )

        model.fit(X_np, y_np)
        predictions = model.predict(X_np)

        metrics: dict[str, Any] = {
            "r2_score": float(r2_score(y_np, predictions)),
            "mse": float(mean_squared_error(y_np, predictions)),
            "rmse": float(np.sqrt(mean_squared_error(y_np, predictions))),
            "mae": float(mean_absolute_error(y_np, predictions)),
            "n_estimators": int(params["n_estimators"]),
        }

        # Feature importances
        metrics["feature_importances"] = {
            col: float(imp) for col, imp in zip(X.columns, model.feature_importances_)
        }

        # OOB score if available
        if hasattr(model, "oob_score_"):
            metrics["oob_score"] = float(model.oob_score_)

        return model, metrics
