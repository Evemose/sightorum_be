"""
Response DTOs for the ML Training Service.

These DTOs provide structured responses for AI-to-AI communication,
including detailed metadata about trained models.
"""

from dataclasses import dataclass, field
from datetime import datetime
from typing import Any, Optional


@dataclass
class ModelMetadata:
    """Metadata about a trained model."""

    model_name: str
    model_type: str
    model_category: str
    feature_columns: list[str]
    target_column: Optional[str]
    training_rows: int
    training_columns: int
    model_params: dict[str, Any]
    training_metrics: dict[str, Any]
    created_at: datetime
    storage_path: str
    model_uuid: Optional[str] = None  # For supervised models (predictions)
    results_uuid: Optional[str] = None  # For unsupervised models (results)

    def to_dict(self) -> dict[str, Any]:
        data = {
            "model_name": self.model_name,
            "model_type": self.model_type,
            "model_category": self.model_category,
            "feature_columns": self.feature_columns,
            "target_column": self.target_column,
            "training_rows": self.training_rows,
            "training_columns": self.training_columns,
            "model_params": self.model_params,
            "training_metrics": self.training_metrics,
            "created_at": self.created_at.isoformat(),
            "storage_path": self.storage_path,
        }

        if self.model_uuid:
            data["model_uuid"] = self.model_uuid
            data["prediction_endpoint"] = f"/predict/{self.model_uuid}"

        if self.results_uuid:
            data["results_uuid"] = self.results_uuid
            data["results_endpoint"] = f"/results/{self.results_uuid}"

        return data


@dataclass
class TrainingResponse:
    """
    Response DTO for model training requests.

    Provides comprehensive information about the training result
    for AI-to-AI communication.
    """

    success: bool
    message: str
    model_metadata: Optional[ModelMetadata] = None
    error_code: Optional[str] = None
    error_details: dict[str, Any] = field(default_factory=dict)
    warnings: list[str] = field(default_factory=list)

    @property
    def metrics(self) -> Optional[dict[str, Any]]:
        """Get training metrics (shortcut to model_metadata.training_metrics)."""
        if self.model_metadata:
            return self.model_metadata.training_metrics
        return None

    @property
    def ai_message(self) -> str:
        """Get AI-friendly message (alias for message)."""
        return self.message

    @classmethod
    def success_response(
            cls,
            model_metadata: ModelMetadata,
            warnings: Optional[list[str]] = None,
    ) -> "TrainingResponse":
        """Create a success response."""
        message_parts = [
            f"Model '{model_metadata.model_name}' trained successfully.",
            f"Type: {model_metadata.model_type} ({model_metadata.model_category}).",
            f"Trained on {model_metadata.training_rows:,} rows with {model_metadata.training_columns} features.",
            f"Saved to: {model_metadata.storage_path}",
        ]
        return cls(
            success=True,
            message=" ".join(message_parts),
            model_metadata=model_metadata,
            warnings=warnings or [],
        )

    @classmethod
    def error_response(
            cls,
            error_code: str,
            message: str,
            details: Optional[dict[str, Any]] = None,
    ) -> "TrainingResponse":
        """Create an error response."""
        return cls(
            success=False,
            message=message,
            error_code=error_code,
            error_details=details or {},
        )

    @classmethod
    def from_exception(cls, exc: Exception) -> "TrainingResponse":
        """Create an error response from an exception."""
        from core.exceptions import MLTrainingError

        if isinstance(exc, MLTrainingError):
            return cls(
                success=False,
                message=exc.message,
                error_code=exc.error_code,
                error_details=exc.details,
            )
        else:
            return cls(
                success=False,
                message=f"Unexpected error: {str(exc)}",
                error_code="INTERNAL_ERROR",
                error_details={"exception_type": type(exc).__name__},
            )

    def to_dict(self) -> dict[str, Any]:
        result: dict[str, Any] = {
            "success": self.success,
            "message": self.message,
        }
        if self.model_metadata:
            result["model_metadata"] = self.model_metadata.to_dict()
        if self.error_code:
            result["error_code"] = self.error_code
        if self.error_details:
            result["error_details"] = self.error_details
        if self.warnings:
            result["warnings"] = self.warnings
        return result

    def to_json(self) -> str:
        """Serialize to JSON string."""
        import json
        return json.dumps(self.to_dict(), indent=2, default=str)
