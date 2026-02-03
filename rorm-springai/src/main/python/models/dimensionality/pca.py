"""Principal Component Analysis model trainer."""

import numpy as np
import polars as pl
from sklearn.decomposition import PCA
from typing import Any, Optional

from ..base import ModelTrainer, ModelCategory


class PCATrainer(ModelTrainer):
    """
    Principal Component Analysis (PCA) trainer.

    Linear dimensionality reduction using SVD to project data
    to a lower dimensional space.
    """

    @property
    def model_type(self) -> str:
        return "pca"

    @property
    def category(self) -> ModelCategory:
        return ModelCategory.DIMENSIONALITY_REDUCTION

    @property
    def requires_target(self) -> bool:
        return False

    @property
    def default_params(self) -> dict[str, Any]:
        return {
            "n_components": 2,
            "whiten": False,
            "svd_solver": "auto",
            "random_state": 42,
        }

    @property
    def param_schema(self) -> dict[str, dict[str, Any]]:
        return {
            "n_components": {
                "type": "int",
                "required": False,
                "default": 2,
                "min": 1,
                "description": "Number of components to keep",
            },
            "whiten": {
                "type": "bool",
                "required": False,
                "default": False,
                "description": "Whether to whiten components (unit variance)",
            },
            "svd_solver": {
                "type": "str",
                "required": False,
                "default": "auto",
                "choices": ["auto", "full", "arpack", "randomized"],
                "description": "SVD solver algorithm",
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
        """Train PCA model."""
        X_np = X.to_numpy()

        # Ensure n_components doesn't exceed number of features
        n_components = min(params["n_components"], X_np.shape[1], X_np.shape[0])

        model = PCA(
            n_components=n_components,
            whiten=params["whiten"],
            svd_solver=params["svd_solver"],
            random_state=params.get("random_state"),
        )

        model.fit(X_np)
        _ = model.transform(X_np)  # Fit for consistency, not used in metrics

        metrics: dict[str, Any] = {
            "n_components": int(n_components),
            "explained_variance_ratio": [
                float(v) for v in model.explained_variance_ratio_
            ],
            "total_explained_variance": float(sum(model.explained_variance_ratio_)),
            "singular_values": [float(v) for v in model.singular_values_],
        }

        # Cumulative explained variance
        cumulative = np.cumsum(model.explained_variance_ratio_)
        metrics["cumulative_explained_variance"] = [float(v) for v in cumulative]

        # Component loadings (feature contributions)
        metrics["component_loadings"] = {
            f"PC{i + 1}": {col: float(loading) for col, loading in zip(X.columns, model.components_[i])}
            for i in range(n_components)
        }

        # Noise variance for probabilistic PCA
        if hasattr(model, "noise_variance_"):
            metrics["noise_variance"] = float(model.noise_variance_)

        return model, metrics
