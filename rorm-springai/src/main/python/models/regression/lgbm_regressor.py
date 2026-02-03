"""LightGBM Regressor model trainer."""

import lightgbm as lgb
import numpy as np
import polars as pl
from sklearn.metrics import mean_squared_error, mean_absolute_error, r2_score
from typing import Any, Optional

from ..base import ModelTrainer, ModelCategory


class LGBMRegressorTrainer(ModelTrainer):
    """
    LightGBM Regressor trainer.

    Gradient boosting framework using tree-based learning algorithms.
    Optimized for speed and efficiency.
    """

    @property
    def model_type(self) -> str:
        return "lgbm_regressor"

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
            "learning_rate": 0.1,
            "max_depth": -1,
            "num_leaves": 31,
            "min_child_samples": 20,
            "subsample": 1.0,
            "colsample_bytree": 1.0,
            "reg_alpha": 0.0,
            "reg_lambda": 0.0,
            "random_state": 42,
            "n_jobs": -1,
            "verbosity": -1,
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
                "description": "Number of boosting iterations",
            },
            "learning_rate": {
                "type": "float",
                "required": False,
                "default": 0.1,
                "min": 0.001,
                "max": 1.0,
                "description": "Boosting learning rate",
            },
            "max_depth": {
                "type": "int",
                "required": False,
                "default": -1,
                "description": "Maximum tree depth (-1 = unlimited)",
            },
            "num_leaves": {
                "type": "int",
                "required": False,
                "default": 31,
                "min": 2,
                "max": 131072,
                "description": "Maximum number of leaves per tree",
            },
            "min_child_samples": {
                "type": "int",
                "required": False,
                "default": 20,
                "min": 1,
                "description": "Minimum samples in a leaf",
            },
            "subsample": {
                "type": "float",
                "required": False,
                "default": 1.0,
                "min": 0.1,
                "max": 1.0,
                "description": "Subsample ratio of training data",
            },
            "colsample_bytree": {
                "type": "float",
                "required": False,
                "default": 1.0,
                "min": 0.1,
                "max": 1.0,
                "description": "Subsample ratio of features",
            },
            "reg_alpha": {
                "type": "float",
                "required": False,
                "default": 0.0,
                "min": 0,
                "description": "L1 regularization term",
            },
            "reg_lambda": {
                "type": "float",
                "required": False,
                "default": 0.0,
                "min": 0,
                "description": "L2 regularization term",
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
                "description": "Number of parallel threads (-1 = all cores)",
            },
        }

    def train(
            self,
            X: pl.DataFrame,
            y: Optional[pl.Series],
            params: dict[str, Any],
    ) -> tuple[Any, dict[str, Any]]:
        """Train LightGBM Regressor model."""
        X_np = X.to_numpy()
        y_np = y.to_numpy()

        model = lgb.LGBMRegressor(
            n_estimators=params["n_estimators"],
            learning_rate=params["learning_rate"],
            max_depth=params["max_depth"],
            num_leaves=params["num_leaves"],
            min_child_samples=params["min_child_samples"],
            subsample=params["subsample"],
            colsample_bytree=params["colsample_bytree"],
            reg_alpha=params["reg_alpha"],
            reg_lambda=params["reg_lambda"],
            random_state=params.get("random_state"),
            n_jobs=params["n_jobs"],
            verbosity=params.get("verbosity", -1),
        )

        model.fit(X_np, y_np)
        predictions = model.predict(X_np)

        metrics = {
            "r2_score": float(r2_score(y_np, predictions)),
            "mse": float(mean_squared_error(y_np, predictions)),
            "rmse": float(np.sqrt(mean_squared_error(y_np, predictions))),
            "mae": float(mean_absolute_error(y_np, predictions)),
            "n_estimators": int(params["n_estimators"]),
            "best_iteration": int(model.best_iteration_) if model.best_iteration_ else params["n_estimators"],
        }

        # Feature importances
        metrics["feature_importances"] = {
            col: int(imp) for col, imp in zip(X.columns, model.feature_importances_)
        }

        return model, metrics
