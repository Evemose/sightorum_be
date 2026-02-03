"""Agglomerative (Hierarchical) clustering model trainer."""

import numpy as np
import polars as pl
from sklearn.cluster import AgglomerativeClustering
from sklearn.metrics import silhouette_score, calinski_harabasz_score, davies_bouldin_score
from typing import Any, Optional

from ..base import ModelTrainer, ModelCategory


class AgglomerativeTrainer(ModelTrainer):
    """
    Agglomerative Hierarchical Clustering trainer.

    Recursively merges clusters based on linkage criteria.
    """

    @property
    def model_type(self) -> str:
        return "agglomerative"

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
            "linkage": "ward",
            "metric": "euclidean",
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
                "description": "Number of clusters to find",
            },
            "linkage": {
                "type": "str",
                "required": False,
                "default": "ward",
                "choices": ["ward", "complete", "average", "single"],
                "description": "Linkage criterion for merging clusters",
            },
            "metric": {
                "type": "str",
                "required": False,
                "default": "euclidean",
                "choices": ["euclidean", "manhattan", "cosine", "l1", "l2"],
                "description": "Distance metric (ignored if linkage=ward)",
            },
        }

    def train(
            self,
            X: pl.DataFrame,
            y: Optional[pl.Series],
            params: dict[str, Any],
    ) -> tuple[Any, dict[str, Any]]:
        """Train Agglomerative clustering model."""
        X_np = X.to_numpy()

        # Ward linkage only works with euclidean distance
        metric = params["metric"]
        if params["linkage"] == "ward":
            metric = "euclidean"

        model = AgglomerativeClustering(
            n_clusters=params["n_clusters"],
            linkage=params["linkage"],
            metric=metric,
        )

        labels = model.fit_predict(X_np)

        metrics: dict[str, Any] = {
            "n_clusters": int(params["n_clusters"]),
            "n_leaves": int(model.n_leaves_),
            "n_connected_components": int(model.n_connected_components_),
        }

        # Compute clustering quality metrics
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
