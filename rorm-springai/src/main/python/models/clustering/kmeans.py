"""KMeans clustering model trainer."""

import numpy as np
import polars as pl
from sklearn.cluster import KMeans
from sklearn.metrics import silhouette_score, calinski_harabasz_score, davies_bouldin_score
from typing import Any, Optional

from ..base import ModelTrainer, ModelCategory


class KMeansTrainer(ModelTrainer):
    """
    KMeans clustering trainer.

    Partitions data into k clusters by minimizing within-cluster variance.
    """

    @property
    def model_type(self) -> str:
        return "kmeans"

    @property
    def category(self) -> ModelCategory:
        return ModelCategory.CLUSTERING

    @property
    def requires_target(self) -> bool:
        return False

    @property
    def default_params(self) -> dict[str, Any]:
        return {
            "n_clusters": 8,
            "init": "k-means++",
            "n_init": 10,
            "max_iter": 300,
            "random_state": 42,
        }

    @property
    def param_schema(self) -> dict[str, dict[str, Any]]:
        return {
            "n_clusters": {
                "type": "int",
                "required": False,
                "default": 8,
                "min": 2,
                "max": 1000,
                "description": "Number of clusters to form",
            },
            "init": {
                "type": "str",
                "required": False,
                "default": "k-means++",
                "choices": ["k-means++", "random"],
                "description": "Initialization method for centroids",
            },
            "n_init": {
                "type": "int",
                "required": False,
                "default": 10,
                "min": 1,
                "max": 100,
                "description": "Number of times to run with different seeds",
            },
            "max_iter": {
                "type": "int",
                "required": False,
                "default": 300,
                "min": 1,
                "max": 10000,
                "description": "Maximum iterations per run",
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
        """Train KMeans model."""
        X_np = X.to_numpy()

        model = KMeans(
            n_clusters=params["n_clusters"],
            init=params["init"],
            n_init=params["n_init"],
            max_iter=params["max_iter"],
            random_state=params.get("random_state"),
        )

        labels = model.fit_predict(X_np)

        # Compute clustering metrics
        metrics: dict[str, Any] = {
            "inertia": float(model.inertia_),
            "n_iter": int(model.n_iter_),
            "n_clusters": int(params["n_clusters"]),
        }

        # Only compute these if we have enough samples and clusters
        if len(np.unique(labels)) > 1:
            metrics["silhouette_score"] = float(silhouette_score(X_np, labels))
            metrics["calinski_harabasz_score"] = float(
                calinski_harabasz_score(X_np, labels)
            )
            metrics["davies_bouldin_score"] = float(
                davies_bouldin_score(X_np, labels)
            )

        # Cluster distribution
        unique, counts = np.unique(labels, return_counts=True)
        metrics["cluster_distribution"] = {
            int(k): int(v) for k, v in zip(unique, counts)
        }

        return model, metrics
