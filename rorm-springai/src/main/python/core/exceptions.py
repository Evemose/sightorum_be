"""
Exception hierarchy for the ML Training Service.

All exceptions provide clear, structured error messages optimized for AI-to-AI communication.
Each exception includes:
- error_code: A unique identifier for the error type
- message: Human/AI readable description
- details: Additional context as a dictionary
"""

from dataclasses import dataclass, field
from typing import Any


@dataclass
class MLTrainingError(Exception):
    """Base exception for all ML Training Service errors."""

    error_code: str
    message: str
    details: dict[str, Any] = field(default_factory=dict)

    def __post_init__(self):
        super().__init__(self.to_message())

    def to_message(self) -> str:
        base = f"[{self.error_code}] {self.message}"
        if self.details:
            detail_str = "; ".join(f"{k}={v}" for k, v in self.details.items())
            base += f" | Details: {detail_str}"
        return base

    def to_dict(self) -> dict[str, Any]:
        return {
            "error_code": self.error_code,
            "message": self.message,
            "details": self.details,
        }


@dataclass
class ValidationError(MLTrainingError):
    """Raised when request validation fails."""

    error_code: str = "VALIDATION_ERROR"
    message: str = "Request validation failed"
    field_errors: dict[str, list[str]] = field(default_factory=dict)

    def __post_init__(self):
        if self.field_errors:
            self.details["field_errors"] = self.field_errors
        super().__post_init__()

    @classmethod
    def missing_field(cls, field_name: str) -> "ValidationError":
        return cls(
            message=f"Required field '{field_name}' is missing or empty",
            field_errors={field_name: ["Field is required"]},
        )

    @classmethod
    def invalid_type(
            cls, field_name: str, expected: str, actual: str
    ) -> "ValidationError":
        return cls(
            message=f"Field '{field_name}' has invalid type: expected {expected}, got {actual}",
            field_errors={field_name: [f"Expected type {expected}, got {actual}"]},
        )

    @classmethod
    def invalid_value(
            cls, field_name: str, value: Any, reason: str
    ) -> "ValidationError":
        return cls(
            message=f"Field '{field_name}' has invalid value: {reason}",
            field_errors={field_name: [reason]},
            details={"provided_value": str(value)},
        )

    @classmethod
    def multiple_errors(cls, errors: dict[str, list[str]]) -> "ValidationError":
        error_count = sum(len(v) for v in errors.values())
        return cls(
            message=f"Validation failed with {error_count} error(s) in {len(errors)} field(s)",
            field_errors=errors,
        )


@dataclass
class DatasourceError(MLTrainingError):
    """Raised when data source operations fail."""

    error_code: str = "DATASOURCE_ERROR"
    message: str = "Data source operation failed"

    @classmethod
    def connection_failed(cls, reason: str) -> "DatasourceError":
        return cls(
            message=f"Failed to connect to data source: {reason}",
            details={"reason": reason},
        )

    @classmethod
    def query_failed(cls, query: str, reason: str) -> "DatasourceError":
        truncated_query = query[:200] + "..." if len(query) > 200 else query
        return cls(
            message=f"Query execution failed: {reason}",
            details={"query_preview": truncated_query, "reason": reason},
        )

    @classmethod
    def query_timeout(cls, timeout_seconds: int) -> "DatasourceError":
        return cls(
            error_code="QUERY_TIMEOUT",
            message=f"Query execution exceeded timeout of {timeout_seconds} seconds",
            details={"timeout_seconds": timeout_seconds},
        )

    @classmethod
    def result_too_large(cls, estimated_rows: int, limit_description: str | int) -> "DatasourceError":
        """
        Create result too large error.

        Args:
            estimated_rows: Estimated number of rows
            limit_description: Either an integer max_rows or a string describing the limit
        """
        if isinstance(limit_description, int):
            # Legacy format: row-based limit
            max_rows = limit_description
            message = f"Estimated result size ({estimated_rows:,} rows) exceeds maximum allowed ({max_rows:,} rows)"
            details = {"estimated_rows": estimated_rows, "max_rows": max_rows}
        else:
            # New format: custom message (usually memory-based)
            message = limit_description
            details = {"estimated_rows": estimated_rows}

        return cls(
            error_code="RESULT_TOO_LARGE",
            message=message,
            details=details,
        )


@dataclass
class ModelNotFoundError(MLTrainingError):
    """Raised when a requested model type is not registered."""

    error_code: str = "MODEL_NOT_FOUND"
    message: str = "Requested model type not found in registry"
    model_type: str = ""
    available_models: list[str] = field(default_factory=list)

    def __post_init__(self):
        if self.model_type:
            self.details["requested_model"] = self.model_type
        if self.available_models:
            self.details["available_models"] = self.available_models
        super().__post_init__()

    @classmethod
    def not_registered(
            cls, model_type: str, available: list[str]
    ) -> "ModelNotFoundError":
        return cls(
            message=f"Model type '{model_type}' is not registered. Available models: {', '.join(available)}",
            model_type=model_type,
            available_models=available,
        )


@dataclass
class ModelTrainingError(MLTrainingError):
    """Raised when model training fails."""

    error_code: str = "MODEL_TRAINING_ERROR"
    message: str = "Model training failed"
    model_type: str = ""

    def __post_init__(self):
        if self.model_type:
            self.details["model_type"] = self.model_type
        super().__post_init__()

    @classmethod
    def missing_target_column(
            cls, model_type: str, column_name: str, available_columns: list[str]
    ) -> "ModelTrainingError":
        return cls(
            message=f"Target column '{column_name}' not found in dataset. Available columns: {', '.join(available_columns)}",
            model_type=model_type,
            details={
                "target_column": column_name,
                "available_columns": available_columns,
            },
        )

    @classmethod
    def insufficient_data(
            cls, model_type: str, row_count: int, minimum_required: int
    ) -> "ModelTrainingError":
        return cls(
            message=f"Insufficient data for training: got {row_count} rows, need at least {minimum_required}",
            model_type=model_type,
            details={"row_count": row_count, "minimum_required": minimum_required},
        )

    @classmethod
    def invalid_parameters(
            cls, model_type: str, param_name: str, reason: str
    ) -> "ModelTrainingError":
        return cls(
            message=f"Invalid model parameter '{param_name}': {reason}",
            model_type=model_type,
            details={"parameter": param_name, "reason": reason},
        )

    @classmethod
    def training_failed(
            cls, model_type: str, reason: str, exception_type: str = ""
    ) -> "ModelTrainingError":
        details = {"reason": reason}
        if exception_type:
            details["exception_type"] = exception_type
        return cls(
            message=f"Training failed for model '{model_type}': {reason}",
            model_type=model_type,
            details=details,
        )


@dataclass
class StorageError(MLTrainingError):
    """Raised when model storage operations fail."""

    error_code: str = "STORAGE_ERROR"
    message: str = "Storage operation failed"

    @classmethod
    def save_failed(cls, model_name: str, reason: str) -> "StorageError":
        return cls(
            message=f"Failed to save model '{model_name}': {reason}",
            details={"model_name": model_name, "reason": reason},
        )

    @classmethod
    def load_failed(cls, model_name: str, reason: str) -> "StorageError":
        return cls(
            message=f"Failed to load model '{model_name}': {reason}",
            details={"model_name": model_name, "reason": reason},
        )

    @classmethod
    def directory_error(cls, path: str, reason: str) -> "StorageError":
        return cls(
            message=f"Storage directory error at '{path}': {reason}",
            details={"path": path, "reason": reason},
        )


@dataclass
class ThrottlingError(MLTrainingError):
    """Raised when request is throttled due to resource constraints."""

    error_code: str = "THROTTLING_ERROR"
    message: str = "Request throttled due to resource constraints"

    @classmethod
    def max_concurrent_reached(
            cls, current: int, maximum: int, wait_seconds: int
    ) -> "ThrottlingError":
        return cls(
            message=f"Maximum concurrent queries reached ({current}/{maximum}). Retry after {wait_seconds} seconds.",
            details={
                "current_queries": current,
                "max_queries": maximum,
                "retry_after_seconds": wait_seconds,
            },
        )


@dataclass
class ConfigurationError(MLTrainingError):
    """Raised when configuration is invalid or missing."""

    error_code: str = "CONFIGURATION_ERROR"
    message: str = "Configuration error"

    @classmethod
    def missing_required(cls, config_key: str) -> "ConfigurationError":
        return cls(
            message=f"Required configuration key '{config_key}' is missing",
            details={"config_key": config_key},
        )

    @classmethod
    def invalid_value(
            cls, config_key: str, value: Any, reason: str
    ) -> "ConfigurationError":
        return cls(
            message=f"Invalid configuration value for '{config_key}': {reason}",
            details={"config_key": config_key, "value": str(value), "reason": reason},
        )


# Alias for backwards compatibility
MLTrainingException = MLTrainingError
