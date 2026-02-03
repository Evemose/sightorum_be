"""Logistic Regression model trainer."""

import numpy as np
import polars as pl
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import accuracy_score, precision_score, recall_score, f1_score, roc_auc_score
from typing import Any, Optional

from ..base import ModelTrainer, ModelCategory


class LogisticRegressionTrainer(ModelTrainer):
    """
    Logistic Regression trainer.

    Linear model for classification using logistic function.
    """

    @property
    def model_type(self) -> str:
        return "logistic_regression"

    @property
    def category(self) -> ModelCategory:
        return ModelCategory.CLASSIFICATION

    @property
    def requires_target(self) -> bool:
        return True

    @property
    def default_params(self) -> dict[str, Any]:
        return {
            "C": 1.0,
            "penalty": "l2",
            "solver": "lbfgs",
            "max_iter": 1000,
            "random_state": 42,
        }

    @property
    def param_schema(self) -> dict[str, dict[str, Any]]:
        return {
            "C": {
                "type": "float",
                "required": False,
                "default": 1.0,
                "min": 0.0001,
                "description": "Inverse of regularization strength (smaller = stronger)",
            },
            "penalty": {
                "type": "str",
                "required": False,
                "default": "l2",
                "choices": ["l1", "l2", "elasticnet", "none"],
                "description": "Regularization penalty type",
            },
            "solver": {
                "type": "str",
                "required": False,
                "default": "lbfgs",
                "choices": ["lbfgs", "liblinear", "newton-cg", "newton-cholesky", "sag", "saga"],
                "description": "Optimization algorithm",
            },
            "max_iter": {
                "type": "int",
                "required": False,
                "default": 1000,
                "min": 1,
                "description": "Maximum iterations for solver convergence",
            },
            "random_state": {
                "type": "int",
                "required": False,
                "default": 42,
                "description": "Random seed for reproducibility",
            },
        }

    def train(
            self,
            X: pl.DataFrame,
            y: Optional[pl.Series],
            params: dict[str, Any],
    ) -> tuple[Any, dict[str, Any]]:
        """Train Logistic Regression model."""
        X_np = X.to_numpy()
        y_np = y.to_numpy()

        # Handle penalty=none properly
        penalty = params["penalty"]
        if penalty == "none":
            penalty = None

        model = LogisticRegression(
            C=params["C"],
            penalty=penalty,
            solver=params["solver"],
            max_iter=params["max_iter"],
            random_state=params.get("random_state"),
        )

        model.fit(X_np, y_np)
        predictions = model.predict(X_np)

        # Determine if binary or multiclass
        n_classes = len(np.unique(y_np))
        average = "binary" if n_classes == 2 else "weighted"

        metrics: dict[str, Any] = {
            "accuracy": float(accuracy_score(y_np, predictions)),
            "precision": float(precision_score(y_np, predictions, average=average, zero_division=0)),
            "recall": float(recall_score(y_np, predictions, average=average, zero_division=0)),
            "f1_score": float(f1_score(y_np, predictions, average=average, zero_division=0)),
            "n_classes": n_classes,
            "n_iter": int(model.n_iter_[0]) if hasattr(model, "n_iter_") else 0,
        }

        # AUC-ROC for binary classification
        if n_classes == 2:
            proba = model.predict_proba(X_np)[:, 1]
            metrics["roc_auc"] = float(roc_auc_score(y_np, proba))

        # Coefficients
        if hasattr(model, "coef_"):
            if model.coef_.shape[0] == 1:
                metrics["coefficients"] = {
                    col: float(coef) for col, coef in zip(X.columns, model.coef_[0])
                }
            else:
                metrics["coefficients_per_class"] = {
                    f"class_{i}": {col: float(coef) for col, coef in zip(X.columns, model.coef_[i])}
                    for i in range(model.coef_.shape[0])
                }

        return model, metrics
