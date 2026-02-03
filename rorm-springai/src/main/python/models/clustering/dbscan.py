"""DBSCAN clustering model trainer."""

import numpy as np
import polars as pl
from sklearn.cluster import DBSCAN
from sklearn.metrics import silhouette_score, calinski_harabasz_score
from typing import Any, Optional

from ..base import ModelTrainer, ModelCategory


class DBSCANTrainer(ModelTrainer):
    """
    DBSCAN (Density-Based Spatial Clustering of Applications with Noise) trainer.

    Finds core samples of high density and expands clusters from them.
    Does not require specifying number of clusters.
    """

    @property
    def model_type(self) -> str:
        return "dbscan"

    @property
    def category(self) -> ModelCategory:
        return ModelCategory.CLUSTERING

    @property
    def requires_target(self) -> bool:
        return False

    @property
    def default_params(self) -> dict[str, Any]:
        return {
            "eps": 0.5,
            "min_samples": 5,
            "metric": "euclidean",
            "algorithm": "auto",
        }

    @property
    def param_schema(self) -> dict[str, dict[str, Any]]:
        return {
            "eps": {
                "type": "float",
                "required": False,
                "default": 0.5,
                "min": 0.0001,
                "description": "Maximum distance between samples in same neighborhood",
            },
            "min_samples": {
                "type": "int",
                "required": False,
                "default": 5,
                "min": 1,
                "description": "Minimum samples in neighborhood for core point",
            },
            "metric": {
                "type": "str",
                "required": False,
                "default": "euclidean",
                "choices": ["euclidean", "manhattan", "cosine", "l1", "l2"],
                "description": "Distance metric for neighborhood computation",
            },
            "algorithm": {
                "type": "str",
                "required": False,
                "default": "auto",
                "choices": ["auto", "ball_tree", "kd_tree", "brute"],
                "description": "Algorithm for nearest neighbors computation",
            },
        }

    def train(
            self,
            X: pl.DataFrame,
            y: Optional[pl.Series],
            params: dict[str, Any],
    ) -> tuple[Any, dict[str, Any]]:
        """Train DBSCAN model."""
        X_np = X.to_numpy()

        model = DBSCAN(
            eps=params["eps"],
            min_samples=params["min_samples"],
            metric=params["metric"],
            algorithm=params["algorithm"],
        )

        labels = model.fit_predict(X_np)

        # Count clusters (excluding noise points labeled as -1)
        n_clusters = len(set(labels)) - (1 if -1 in labels else 0)
        n_noise = list(labels).count(-1)

        metrics: dict[str, Any] = {
            "n_clusters_found": n_clusters,
            "n_noise_points": n_noise,
            "noise_ratio": float(n_noise / len(labels)) if len(labels) > 0 else 0,
        }

        # Only compute quality metrics if we have at least 2 clusters and non-noise points
        non_noise_mask = labels != -1
        if n_clusters > 1 and np.sum(non_noise_mask) > 0:
            X_filtered = X_np[non_noise_mask]
            labels_filtered = labels[non_noise_mask]
            if len(np.unique(labels_filtered)) > 1:
                metrics["silhouette_score"] = float(
                    silhouette_score(X_filtered, labels_filtered)
                )
                metrics["calinski_harabasz_score"] = float(
                    calinski_harabasz_score(X_filtered, labels_filtered)
                )

        # Cluster distribution
        unique, counts = np.unique(labels, return_counts=True)
        metrics["cluster_distribution"] = {
            int(k): int(v) for k, v in zip(unique, counts)
        }

        return model, metrics
