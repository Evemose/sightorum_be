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
