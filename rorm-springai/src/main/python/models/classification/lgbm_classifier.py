"""LightGBM Classifier model trainer."""

import lightgbm as lgb
import numpy as np
import polars as pl
from sklearn.metrics import accuracy_score, precision_score, recall_score, f1_score, roc_auc_score
from typing import Any, Optional

from ..base import ModelTrainer, ModelCategory


class LGBMClassifierTrainer(ModelTrainer):
    """
    LightGBM Classifier trainer.

    Gradient boosting framework for classification.
    Optimized for speed and efficiency.
    """

    @property
    def model_type(self) -> str:
        return "lgbm_classifier"

    @property
    def category(self) -> ModelCategory:
        return ModelCategory.CLASSIFICATION

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
            "class_weight": None,
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
            "class_weight": {
                "type": "str",
                "required": False,
                "default": None,
                "choices": ["balanced"],
                "description": "Weights for classes (balanced adjusts for imbalance)",
            },
        }

    def train(
            self,
            X: pl.DataFrame,
            y: Optional[pl.Series],
            params: dict[str, Any],
    ) -> tuple[Any, dict[str, Any]]:
        """Train LightGBM Classifier model."""
        X_np = X.to_numpy()
        y_np = y.to_numpy()

        model = lgb.LGBMClassifier(
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
            class_weight=params.get("class_weight"),
        )

        model.fit(X_np, y_np)
        predictions = model.predict(X_np)

        # Determine if binary or multiclass
        n_classes = len(np.unique(y_np))
        average = "binary" if n_classes == 2 else "weighted"

        metrics = {
            "accuracy": float(accuracy_score(y_np, predictions)),
            "precision": float(precision_score(y_np, predictions, average=average, zero_division=0)),
            "recall": float(recall_score(y_np, predictions, average=average, zero_division=0)),
            "f1_score": float(f1_score(y_np, predictions, average=average, zero_division=0)),
            "n_classes": n_classes,
            "n_estimators": int(params["n_estimators"]),
            "best_iteration": int(model.best_iteration_) if model.best_iteration_ else params["n_estimators"],
        }

        # AUC-ROC for binary classification
        if n_classes == 2:
            proba = model.predict_proba(X_np)[:, 1]
            metrics["roc_auc"] = float(roc_auc_score(y_np, proba))

        # Feature importances
        metrics["feature_importances"] = {
            col: int(imp) for col, imp in zip(X.columns, model.feature_importances_)
        }

        # Class distribution in predictions
        unique, counts = np.unique(predictions, return_counts=True)
        metrics["prediction_distribution"] = {
            str(k): int(v) for k, v in zip(unique, counts)
        }

        return model, metrics
