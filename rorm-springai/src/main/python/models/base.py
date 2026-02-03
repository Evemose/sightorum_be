"""
Base classes for model trainers.

Provides abstract interfaces that all model trainers must implement,
ensuring consistent behavior across different model types.
"""

import polars as pl
from abc import ABC, abstractmethod
from core.exceptions import ModelTrainingError, ValidationError
from core.validation import ValidationResult
from dataclasses import dataclass, field
from datetime import datetime
from enum import Enum
from typing import Any, Optional


class ModelCategory(str, Enum):
    """Categories of ML models."""

    CLUSTERING = "clustering"
    REGRESSION = "regression"
    CLASSIFICATION = "classification"
    DIMENSIONALITY_REDUCTION = "dimensionality_reduction"
    DIMENSIONALITY = "dimensionality_reduction"  # Alias for compatibility
    TEMPORAL = "temporal"
    ASSOCIATION = "association"


@dataclass
class TrainedModel:
    """Container for a trained model with metadata."""

    model: Any
    model_name: str
    model_type: str
    category: ModelCategory
    feature_columns: list[str]
    target_column: Optional[str]
    training_rows: int
    training_columns: int
    model_params: dict[str, Any]
    training_metrics: dict[str, Any]
    created_at: datetime = field(default_factory=datetime.now)

    def get_metadata(self) -> dict[str, Any]:
        return {
            "model_name": self.model_name,
            "model_type": self.model_type,
            "category": self.category.value,
            "feature_columns": self.feature_columns,
            "target_column": self.target_column,
            "training_rows": self.training_rows,
            "training_columns": self.training_columns,
            "model_params": self.model_params,
            "training_metrics": self.training_metrics,
            "created_at": self.created_at.isoformat(),
        }


class ModelTrainer(ABC):
    """
    Abstract base class for model trainers.

    Each model trainer is responsible for:
    1. Validating model-specific parameters
    2. Preparing data for training
    3. Training the model
    4. Computing training metrics
    """

    @property
    @abstractmethod
    def model_type(self) -> str:
        """Return the model type identifier."""
        pass

    @property
    @abstractmethod
    def category(self) -> ModelCategory:
        """Return the model category."""
        pass

    @property
    @abstractmethod
    def requires_target(self) -> bool:
        """Return True if model requires a target column."""
        pass

    @property
    def default_params(self) -> dict[str, Any]:
        """Return default parameters for this model."""
        return {}

    @property
    def param_schema(self) -> dict[str, dict[str, Any]]:
        """
        Return parameter schema for validation.

        Schema format:
        {
            "param_name": {
                "type": "int" | "float" | "str" | "bool" | "list",
                "required": bool,
                "default": Any,
                "min": float (optional),
                "max": float (optional),
                "choices": list (optional),
                "description": str
            }
        }
        """
        return {}

    @property
    def minimum_rows(self) -> int:
        """Minimum number of rows required for training."""
        return 10

    def validate_params(self, params: dict[str, Any]) -> ValidationResult:
        """
        Validate model-specific parameters.

        Args:
            params: Parameters to validate

        Returns:
            ValidationResult with any errors
        """
        result = ValidationResult()
        schema = self.param_schema

        for param_name, param_config in schema.items():
            value = params.get(param_name)

            # Check required
            if param_config.get("required", False) and value is None:
                result.add_error(
                    f"model_params.{param_name}",
                    f"Parameter '{param_name}' is required. {param_config.get('description', '')}",
                )
                continue

            if value is None:
                continue

            # Check type
            expected_type = param_config.get("type")
            if expected_type:
                type_valid = self._check_type(value, expected_type)
                if not type_valid:
                    result.add_error(
                        f"model_params.{param_name}",
                        f"Parameter '{param_name}' must be of type {expected_type}, "
                        f"got {type(value).__name__}",
                    )
                    continue

            # Check min/max for numeric types
            if expected_type in ("int", "float"):
                if "min" in param_config and value < param_config["min"]:
                    result.add_error(
                        f"model_params.{param_name}",
                        f"Parameter '{param_name}' must be >= {param_config['min']}, got {value}",
                    )
                if "max" in param_config and value > param_config["max"]:
                    result.add_error(
                        f"model_params.{param_name}",
                        f"Parameter '{param_name}' must be <= {param_config['max']}, got {value}",
                    )

            # Check choices
            if "choices" in param_config and value not in param_config["choices"]:
                result.add_error(
                    f"model_params.{param_name}",
                    f"Parameter '{param_name}' must be one of {param_config['choices']}, got '{value}'",
                )

        return result

    def _check_type(self, value: Any, expected_type: str) -> bool:
        """Check if value matches expected type."""
        type_map = {
            "int": int,
            "float": (int, float),
            "str": str,
            "bool": bool,
            "list": list,
        }
        expected = type_map.get(expected_type)
        if expected is None:
            return True
        return isinstance(value, expected)

    def prepare_data(
            self,
            df: pl.DataFrame,
            feature_columns: Optional[list[str]],
            target_column: Optional[str],
    ) -> tuple[pl.DataFrame, Optional[pl.Series]]:
        """
        Prepare data for training.

        Args:
            df: Input DataFrame
            feature_columns: Columns to use as features (None = all except target)
            target_column: Target column name (None for unsupervised)

        Returns:
            Tuple of (features DataFrame, target Series or None)

        Raises:
            ModelTrainingError: If data preparation fails
        """
        available_columns = df.columns

        # Validate target column
        if self.requires_target:
            if target_column is None:
                raise ModelTrainingError(
                    model_type=self.model_type,
                    message=f"Target column is required for {self.model_type}",
                )
            if target_column not in available_columns:
                raise ModelTrainingError.missing_target_column(
                    self.model_type, target_column, available_columns
                )

        # Determine feature columns
        if feature_columns is None:
            if target_column:
                feature_columns = [c for c in available_columns if c != target_column]
            else:
                feature_columns = available_columns
        else:
            # Validate feature columns exist
            missing = [c for c in feature_columns if c not in available_columns]
            if missing:
                raise ModelTrainingError(
                    model_type=self.model_type,
                    message=f"Feature columns not found in dataset: {', '.join(missing)}",
                    details={
                        "missing_columns": missing,
                        "available_columns": available_columns,
                    },
                )

        # Check minimum rows
        if df.height < self.minimum_rows:
            raise ModelTrainingError.insufficient_data(
                self.model_type, df.height, self.minimum_rows
            )

        # Extract features and target
        X = df.select(feature_columns)
        y = df[target_column] if target_column else None

        return X, y

    @abstractmethod
    def train(
            self,
            X: pl.DataFrame,
            y: Optional[pl.Series],
            params: dict[str, Any],
    ) -> tuple[Any, dict[str, Any]]:
        """
        Train the model.

        Args:
            X: Feature DataFrame
            y: Target Series (None for unsupervised)
            params: Model parameters (merged with defaults)

        Returns:
            Tuple of (trained model, training metrics dict)

        Raises:
            ModelTrainingError: If training fails
        """
        pass

    def fit(
            self,
            df: pl.DataFrame,
            model_name: str,
            feature_columns: Optional[list[str]] = None,
            target_column: Optional[str] = None,
            params: Optional[dict[str, Any]] = None,
    ) -> TrainedModel:
        """
        Full training pipeline.

        Args:
            df: Input DataFrame
            model_name: Name for the trained model
            feature_columns: Columns to use as features
            target_column: Target column name
            params: Model parameters

        Returns:
            TrainedModel containing the model and metadata

        Raises:
            ModelTrainingError: If training fails
            ValidationError: If parameters are invalid
        """
        # Merge with defaults
        final_params = {**self.default_params, **(params or {})}

        # Validate parameters
        validation_result = self.validate_params(final_params)
        validation_result.raise_if_invalid()

        # Prepare data
        X, y = self.prepare_data(df, feature_columns, target_column)
        actual_features = X.columns

        try:
            # Train model
            model, metrics = self.train(X, y, final_params)

            return TrainedModel(
                model=model,
                model_name=model_name,
                model_type=self.model_type,
                category=self.category,
                feature_columns=actual_features,
                target_column=target_column,
                training_rows=X.height,
                training_columns=X.width,
                model_params=final_params,
                training_metrics=metrics,
            )
        except Exception as e:
            if isinstance(e, (ModelTrainingError, ValidationError)):
                raise
            raise ModelTrainingError.training_failed(
                self.model_type, str(e), type(e).__name__
            )
