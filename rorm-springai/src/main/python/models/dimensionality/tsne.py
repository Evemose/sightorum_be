"""t-SNE dimensionality reduction model trainer."""

import numpy as np
import polars as pl
from sklearn.manifold import TSNE
from typing import Any, Optional

from ..base import ModelTrainer, ModelCategory


class TSNETrainer(ModelTrainer):
    """
    t-Distributed Stochastic Neighbor Embedding (t-SNE) trainer.

    Non-linear dimensionality reduction for visualization.
    Best for visualizing high-dimensional data in 2D or 3D.
    """

    @property
    def model_type(self) -> str:
        return "tsne"

    @property
    def category(self) -> ModelCategory:
        return ModelCategory.DIMENSIONALITY_REDUCTION

    @property
    def requires_target(self) -> bool:
        return False

    @property
    def minimum_rows(self) -> int:
        return 50  # t-SNE needs more samples for meaningful results

    @property
    def default_params(self) -> dict[str, Any]:
        return {
            "n_components": 2,
            "perplexity": 30.0,
            "learning_rate": "auto",
            "n_iter": 1000,
            "metric": "euclidean",
            "init": "pca",
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
                "max": 3,
                "description": "Number of dimensions in output (usually 2 or 3)",
            },
            "perplexity": {
                "type": "float",
                "required": False,
                "default": 30.0,
                "min": 5,
                "max": 50,
                "description": "Balance between local and global structure (5-50)",
            },
            "learning_rate": {
                "type": "str",
                "required": False,
                "default": "auto",
                "choices": ["auto"],
                "description": "Learning rate (auto = max(n_samples/early_exaggeration/4, 50))",
            },
            "n_iter": {
                "type": "int",
                "required": False,
                "default": 1000,
                "min": 250,
                "max": 10000,
                "description": "Maximum optimization iterations",
            },
            "metric": {
                "type": "str",
                "required": False,
                "default": "euclidean",
                "choices": ["euclidean", "manhattan", "cosine"],
                "description": "Distance metric for computing similarities",
            },
            "init": {
                "type": "str",
                "required": False,
                "default": "pca",
                "choices": ["pca", "random"],
                "description": "Initialization method (pca is more stable)",
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
        """Train t-SNE model."""
        X_np = X.to_numpy()

        # Adjust perplexity if needed (must be less than n_samples)
        perplexity = min(params["perplexity"], X_np.shape[0] - 1)

        # Handle learning_rate
        learning_rate = params["learning_rate"]
        if learning_rate == "auto":
            learning_rate = "auto"
        else:
            learning_rate = float(learning_rate)

        model = TSNE(
            n_components=params["n_components"],
            perplexity=perplexity,
            learning_rate=learning_rate,
            max_iter=params["n_iter"],
            metric=params["metric"],
            init=params["init"],
            random_state=params.get("random_state"),
        )

        # t-SNE doesn't have a separate fit/transform - it's fit_transform only
        embedded = model.fit_transform(X_np)

        metrics: dict[str, Any] = {
            "n_components": int(params["n_components"]),
            "perplexity": float(perplexity),
            "n_iter": int(params["n_iter"]),
            "kl_divergence": float(model.kl_divergence_),
            "n_iter_final": int(model.n_iter_),
        }

        # Embedding statistics
        for i in range(params["n_components"]):
            col = embedded[:, i]
            metrics[f"component_{i + 1}_range"] = {
                "min": float(col.min()),
                "max": float(col.max()),
                "mean": float(col.mean()),
                "std": float(col.std()),
            }

        return model, metrics
