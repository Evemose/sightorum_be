"""Random Forest Classifier model trainer."""

import numpy as np
import polars as pl
from sklearn.ensemble import RandomForestClassifier
from sklearn.metrics import accuracy_score, precision_score, recall_score, f1_score, roc_auc_score
from typing import Any, Optional

from ..base import ModelTrainer, ModelCategory


class RandomForestClassifierTrainer(ModelTrainer):
    """
    Random Forest Classifier trainer.

    Ensemble of decision trees with bagging for classification.
    """

    @property
    def model_type(self) -> str:
        return "random_forest_classifier"

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
            "max_depth": None,
            "min_samples_split": 2,
            "min_samples_leaf": 1,
            "max_features": "sqrt",
            "random_state": 42,
            "n_jobs": -1,
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
            "class_weight": {
                "type": "str",
                "required": False,
                "default": None,
                "choices": ["balanced", "balanced_subsample"],
                "description": "Weights for classes (balanced adjusts for imbalance)",
            },
        }

    def train(
            self,
            X: pl.DataFrame,
            y: Optional[pl.Series],
            params: dict[str, Any],
    ) -> tuple[Any, dict[str, Any]]:
        """Train Random Forest Classifier model."""
        X_np = X.to_numpy()
        y_np = y.to_numpy()

        model = RandomForestClassifier(
            n_estimators=params["n_estimators"],
            max_depth=params.get("max_depth"),
            min_samples_split=params["min_samples_split"],
            min_samples_leaf=params["min_samples_leaf"],
            max_features=params["max_features"],
            random_state=params.get("random_state"),
            n_jobs=params["n_jobs"],
            class_weight=params.get("class_weight"),
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
            "n_estimators": int(params["n_estimators"]),
        }

        # AUC-ROC for binary classification
        if n_classes == 2:
            proba = model.predict_proba(X_np)[:, 1]
            metrics["roc_auc"] = float(roc_auc_score(y_np, proba))

        # Feature importances
        metrics["feature_importances"] = {
            col: float(imp) for col, imp in zip(X.columns, model.feature_importances_)
        }

        # Class distribution in predictions
        unique, counts = np.unique(predictions, return_counts=True)
        metrics["prediction_distribution"] = {
            str(k): int(v) for k, v in zip(unique, counts)
        }

        return model, metrics
