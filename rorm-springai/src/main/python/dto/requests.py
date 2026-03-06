"""
Request DTOs for the ML Training Service.

These DTOs define the contract for AI-to-AI communication,
with comprehensive validation and clear error messages.
"""

from core.validation import (
    ValidationResult,
    Validator,
    validate_sql_query,
    validate_model_name,
)
from dataclasses import dataclass, field
from typing import Any, Optional


@dataclass
class SQLDatasourceConfig:
    """Configuration for SQL-based data source."""

    sql: str
    bind_variables: dict[str, Any] = field(default_factory=dict)

    def validate(self) -> ValidationResult:
        result = ValidationResult()

        # Validate SQL
        sql_result = validate_sql_query(self.sql)
        result.merge(sql_result)

        # Validate bind variables
        if self.bind_variables:
            validator = Validator()
            validator.field("bind_variables", self.bind_variables).is_type(
                dict, "object/dictionary"
            )
            result.merge(validator.validate())

            for key, value in self.bind_variables.items():
                if not isinstance(key, str):
                    result.add_error(
                        "bind_variables",
                        f"Bind variable key must be a string, got {type(key).__name__}",
                    )
                if value is None:
                    result.add_error(
                        "bind_variables",
                        f"Bind variable '{key}' cannot be None. Use explicit NULL in SQL if needed.",
                    )

        return result

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> "SQLDatasourceConfig":
        return cls(
            sql=data.get("sql", ""),
            bind_variables=data.get("bind_variables", {}),
        )


@dataclass
class TrainingRequest:
    """
    Main request DTO for model training.

    Attributes:
        model_type: The type/name of model to train (e.g., "kmeans", "random_forest_regressor")
        model_name: Unique name for the trained model (used for storage)
        datasource: Data source configuration (currently SQL only)
        target_column: Name of the target/label column (required for supervised learning)
        feature_columns: Optional list of columns to use as features (default: all non-target)
        model_params: Model-specific hyperparameters
    """

    model_type: str
    model_name: str
    datasource: SQLDatasourceConfig
    target_column: Optional[str] = None
    feature_columns: Optional[list[str]] = None
    model_params: dict[str, Any] = field(default_factory=dict)

    def validate(self, available_models: list[str]) -> ValidationResult:
        """
        Validate the training request.

        Args:
            available_models: List of registered model types for validation

        Returns:
            ValidationResult with any validation errors
        """
        result = ValidationResult()

        # Validate model_type
        validator = Validator()
        validator.field("model_type", self.model_type).required(
            "model_type is required. Specify the ML model to train."
        ).is_type(str).in_list(
            available_models,
            f"Unknown model type '{self.model_type}'. Available models: {', '.join(sorted(available_models))}",
        )
        result.merge(validator.validate())

        # Validate model_name
        name_result = validate_model_name(self.model_name)
        result.merge(name_result)

        # Validate datasource
        if self.datasource is None:
            result.add_error("datasource", "datasource configuration is required")
        else:
            datasource_result = self.datasource.validate()
            result.merge(datasource_result)

        # Validate target_column for supervised models
        supervised_models = {
            "linear_regression",
            "ridge_regression",
            "random_forest_regressor",
            "lgbm_regressor",
            "logistic_regression",
            "random_forest_classifier",
            "svm_classifier",
            "lgbm_classifier",
        }

        if self.model_type in supervised_models:
            if not self.target_column:
                result.add_error(
                    "target_column",
                    f"target_column is required for supervised model '{self.model_type}'. "
                    "Specify the column containing labels/targets.",
                )

        # Validate feature_columns if provided
        if self.feature_columns is not None:
            if not isinstance(self.feature_columns, list):
                result.add_error(
                    "feature_columns",
                    f"feature_columns must be a list, got {type(self.feature_columns).__name__}",
                )
            elif len(self.feature_columns) == 0:
                result.add_error(
                    "feature_columns",
                    "feature_columns list cannot be empty. Either omit it to use all columns, "
                    "or provide specific column names.",
                )
            else:
                for i, col in enumerate(self.feature_columns):
                    if not isinstance(col, str):
                        result.add_error(
                            "feature_columns",
                            f"feature_columns[{i}] must be a string, got {type(col).__name__}",
                        )
                    elif not col.strip():
                        result.add_error(
                            "feature_columns",
                            f"feature_columns[{i}] cannot be empty",
                        )

                # Check for duplicates
                if len(self.feature_columns) != len(set(self.feature_columns)):
                    seen = set()
                    duplicates = []
                    for col in self.feature_columns:
                        if col in seen:
                            duplicates.append(col)
                        seen.add(col)
                    result.add_error(
                        "feature_columns",
                        f"Duplicate columns found: {', '.join(duplicates)}",
                    )

        # Validate model_params
        if self.model_params and not isinstance(self.model_params, dict):
            result.add_error(
                "model_params",
                f"model_params must be an object/dictionary, got {type(self.model_params).__name__}",
            )

        return result

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> "TrainingRequest":
        """
        Create a TrainingRequest from a dictionary.

        Args:
            data: Dictionary containing request fields

        Returns:
            TrainingRequest instance

        Raises:
            ValidationError: If required fields are missing or invalid types
        """
        from core.exceptions import ValidationError

        if not isinstance(data, dict):
            raise ValidationError(
                message=f"Request must be a JSON object, got {type(data).__name__}",
                field_errors={"request": ["Expected JSON object"]},
            )

        datasource_data = data.get("datasource")
        if datasource_data is None:
            datasource = SQLDatasourceConfig(sql="")
        elif isinstance(datasource_data, dict):
            datasource = SQLDatasourceConfig.from_dict(datasource_data)
        else:
            raise ValidationError(
                message="datasource must be an object",
                field_errors={
                    "datasource": [
                        f"Expected object, got {type(datasource_data).__name__}"
                    ]
                },
            )

        return cls(
            model_type=data.get("model_type", ""),
            model_name=data.get("model_name", ""),
            datasource=datasource,
            target_column=data.get("target_column"),
            feature_columns=data.get("feature_columns"),
            model_params=data.get("model_params", {}),
        )

    def to_dict(self) -> dict[str, Any]:
        """Convert to dictionary for serialization."""
        return {
            "model_type": self.model_type,
            "model_name": self.model_name,
            "datasource": {
                "sql": self.datasource.sql,
                "bind_variables": self.datasource.bind_variables,
            },
            "target_column": self.target_column,
            "feature_columns": self.feature_columns,
            "model_params": self.model_params,
        }


@dataclass
class StabilitySelectionRequest:
    """
    Request DTO for stability selection analysis.

    Runs repeated subsample fits across multiple model families and
    compares feature importance stability for regression or classification.
    """

    datasource: SQLDatasourceConfig
    target_column: str
    feature_columns: Optional[list[str]] = None
    problem_type: Optional[str] = None  # "regression" | "classification"
    bootstrap_runs: int = 50
    sample_fraction: float = 0.8
    correlation_threshold: float = 0.8
    polynomial_degree: int = 2
    selection_top_k: Optional[int] = None
    random_state: int = 42

    def validate(self) -> ValidationResult:
        """Validate the stability selection request."""
        result = ValidationResult()

        if self.datasource is None:
            result.add_error("datasource", "datasource configuration is required")
        else:
            result.merge(self.datasource.validate())

        target_validator = Validator()
        target_validator.field("target_column", self.target_column).required(
            "target_column is required. Specify the outcome column to analyze."
        ).is_type(str)
        result.merge(target_validator.validate())

        if self.problem_type is not None:
            problem_validator = Validator()
            problem_validator.field("problem_type", self.problem_type).is_type(str).in_list(
                ["regression", "classification"],
                "problem_type must be either 'regression' or 'classification'",
            )
            result.merge(problem_validator.validate())

        if self.feature_columns is not None:
            if not isinstance(self.feature_columns, list):
                result.add_error(
                    "feature_columns",
                    f"feature_columns must be a list, got {type(self.feature_columns).__name__}",
                )
            elif len(self.feature_columns) == 0:
                result.add_error(
                    "feature_columns",
                    "feature_columns list cannot be empty. Omit it to use all non-target columns.",
                )
            else:
                seen = set()
                duplicates = []
                for index, column in enumerate(self.feature_columns):
                    if not isinstance(column, str):
                        result.add_error(
                            "feature_columns",
                            f"feature_columns[{index}] must be a string, got {type(column).__name__}",
                        )
                        continue
                    if not column.strip():
                        result.add_error(
                            "feature_columns",
                            f"feature_columns[{index}] cannot be empty",
                        )
                    if column in seen:
                        duplicates.append(column)
                    seen.add(column)
                if duplicates:
                    result.add_error(
                        "feature_columns",
                        f"Duplicate columns found: {', '.join(sorted(set(duplicates)))}",
                    )
                if self.target_column in self.feature_columns:
                    result.add_error(
                        "feature_columns",
                        "feature_columns must not include target_column",
                    )

        bootstrap_validator = Validator()
        bootstrap_validator.field("bootstrap_runs", self.bootstrap_runs).is_type(int).min_value(
            50, "bootstrap_runs must be at least 50"
        )
        result.merge(bootstrap_validator.validate())

        if not isinstance(self.sample_fraction, (int, float)):
            result.add_error("sample_fraction", "sample_fraction must be a number")
        else:
            sample_validator = Validator()
            sample_validator.field("sample_fraction", float(self.sample_fraction)).min_value(
                0.5, "sample_fraction must be at least 0.5"
            ).max_value(
                1.0, "sample_fraction must be at most 1.0"
            )
            result.merge(sample_validator.validate())

        if not isinstance(self.correlation_threshold, (int, float)):
            result.add_error("correlation_threshold", "correlation_threshold must be a number")
        else:
            correlation_validator = Validator()
            correlation_validator.field(
                "correlation_threshold",
                float(self.correlation_threshold),
            ).min_value(
                0.0, "correlation_threshold must be between 0 and 1"
            ).max_value(
                1.0, "correlation_threshold must be between 0 and 1"
            )
            result.merge(correlation_validator.validate())

        polynomial_validator = Validator()
        polynomial_validator.field("polynomial_degree", self.polynomial_degree).is_type(int).min_value(
            1, "polynomial_degree must be at least 1"
        ).max_value(3, "polynomial_degree must be at most 3 to avoid feature explosion")
        polynomial_validator.field("random_state", self.random_state).is_type(int)
        result.merge(polynomial_validator.validate())

        if self.selection_top_k is not None:
            top_k_validator = Validator()
            top_k_validator.field("selection_top_k", self.selection_top_k).is_type(int).min_value(
                1, "selection_top_k must be at least 1"
            )
            result.merge(top_k_validator.validate())

        return result

    def to_dict(self) -> dict[str, Any]:
        """Convert to dictionary for serialization."""
        return {
            "datasource": {
                "sql": self.datasource.sql,
                "bind_variables": self.datasource.bind_variables,
            },
            "target_column": self.target_column,
            "feature_columns": self.feature_columns,
            "problem_type": self.problem_type,
            "bootstrap_runs": self.bootstrap_runs,
            "sample_fraction": self.sample_fraction,
            "correlation_threshold": self.correlation_threshold,
            "polynomial_degree": self.polynomial_degree,
            "selection_top_k": self.selection_top_k,
            "random_state": self.random_state,
        }
