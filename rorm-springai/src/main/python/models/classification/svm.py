"""Support Vector Machine Classifier model trainer."""

import numpy as np
import polars as pl
from sklearn.metrics import accuracy_score, precision_score, recall_score, f1_score, roc_auc_score
from sklearn.svm import SVC
from typing import Any, Optional

from ..base import ModelTrainer, ModelCategory


class SVMClassifierTrainer(ModelTrainer):
    """
    Support Vector Machine Classifier trainer.

    Finds optimal hyperplane for classification.
    """

    @property
    def model_type(self) -> str:
        return "svm_classifier"

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
            "kernel": "rbf",
            "gamma": "scale",
            "probability": True,
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
                "description": "Regularization parameter (smaller = stronger regularization)",
            },
            "kernel": {
                "type": "str",
                "required": False,
                "default": "rbf",
                "choices": ["linear", "poly", "rbf", "sigmoid"],
                "description": "Kernel type for transformation",
            },
            "gamma": {
                "type": "str",
                "required": False,
                "default": "scale",
                "choices": ["scale", "auto"],
                "description": "Kernel coefficient (scale=1/(n_features*var))",
            },
            "degree": {
                "type": "int",
                "required": False,
                "default": 3,
                "min": 1,
                "max": 10,
                "description": "Degree for polynomial kernel",
            },
            "probability": {
                "type": "bool",
                "required": False,
                "default": True,
                "description": "Enable probability estimates (slower but enables AUC)",
            },
            "random_state": {
                "type": "int",
                "required": False,
                "default": 42,
                "description": "Random seed for reproducibility",
            },
        }

    @property
    def minimum_rows(self) -> int:
        return 20  # SVM needs more samples

    def train(
            self,
            X: pl.DataFrame,
            y: Optional[pl.Series],
            params: dict[str, Any],
    ) -> tuple[Any, dict[str, Any]]:
        """Train SVM Classifier model."""
        X_np = X.to_numpy()
        y_np = y.to_numpy()

        model = SVC(
            C=params["C"],
            kernel=params["kernel"],
            gamma=params["gamma"],
            degree=params.get("degree", 3),
            probability=params["probability"],
            random_state=params.get("random_state"),
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
            "n_support_vectors": int(sum(model.n_support_)),
            "kernel": params["kernel"],
        }

        # AUC-ROC for binary classification with probability enabled
        if n_classes == 2 and params["probability"]:
            proba = model.predict_proba(X_np)[:, 1]
            metrics["roc_auc"] = float(roc_auc_score(y_np, proba))

        # Support vectors per class
        metrics["support_vectors_per_class"] = {
            str(i): int(n) for i, n in enumerate(model.n_support_)
        }

        return model, metrics
