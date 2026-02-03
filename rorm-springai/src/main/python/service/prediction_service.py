"""Prediction service for trained supervised models."""

import logging
import polars as pl
from core.exceptions import (
    ModelNotFoundError,
    ValidationError,
    ModelTrainingError
)
from models.base import ModelCategory
from storage.db_storage import DatabaseModelStorage
from typing import Dict, Any, Optional, List

logger = logging.getLogger(__name__)


class PredictionService:
    """Service for making predictions with trained supervised models."""

    def __init__(self, db_storage: DatabaseModelStorage):
        """
        Initialize prediction service.

        Args:
            db_storage: Database storage for models
        """
        self.db_storage = db_storage

    def predict(
            self,
            model_uuid: str,
            input_data: Dict[str, Any] | List[Dict[str, Any]]
    ) -> Dict[str, Any]:
        """
        Make predictions using a trained model.

        Args:
            model_uuid: UUID of the trained model
            input_data: Single dict or list of dicts with feature values

        Returns:
            Dictionary with predictions and metadata
        """
        # Load model from database
        trained_model = self.db_storage.load_supervised_model(model_uuid)

        if not trained_model:
            raise ModelNotFoundError(
                model_type=model_uuid,
                message=f"Model with UUID {model_uuid} not found in database",
                available_models=[]
            )

        # Check if model is supervised (can make predictions)
        if trained_model.category not in [
            ModelCategory.REGRESSION,
            ModelCategory.CLASSIFICATION
        ]:
            raise ValidationError(
                message=f"Model category {trained_model.category.value} does not support predictions",
                field_errors={
                    "model_uuid": [
                        f"Only regression and classification models support predictions. "
                        f"This model is {trained_model.category.value}."
                    ]
                }
            )

        # Convert input to DataFrame
        if isinstance(input_data, dict):
            input_data = [input_data]

        try:
            df = pl.DataFrame(input_data)
        except Exception as e:
            raise ValidationError(
                message="Invalid input data format",
                field_errors={
                    "input_data": [f"Could not parse input data: {str(e)}"]
                }
            )

        # Validate required features are present
        required_features = trained_model.feature_columns or []
        missing_features = [f for f in required_features if f not in df.columns]

        if missing_features:
            raise ValidationError(
                message="Missing required features",
                field_errors={
                    "input_data": [
                        f"Missing features: {', '.join(missing_features)}. "
                        f"Required features: {', '.join(required_features)}"
                    ]
                }
            )

        # Select and order features as expected by model
        if required_features:
            X = df.select(required_features).to_pandas()
        else:
            # If no feature columns specified, use all columns
            X = df.to_pandas()

        # Make predictions
        try:
            predictions = trained_model.model.predict(X)

            # For classifiers, also get probabilities if available
            probabilities = None
            if trained_model.category == ModelCategory.CLASSIFICATION:
                if hasattr(trained_model.model, 'predict_proba'):
                    probabilities = trained_model.model.predict_proba(X).tolist()

            # Build response
            response = {
                "model_uuid": model_uuid,
                "model_name": trained_model.model_name,
                "model_type": trained_model.model_type,
                "predictions": predictions.tolist() if hasattr(predictions, 'tolist') else list(predictions),
                "num_predictions": len(predictions)
            }

            if probabilities is not None:
                response["probabilities"] = probabilities

            return response

        except Exception as e:
            logger.exception(f"Prediction failed for model {model_uuid}")
            raise ModelTrainingError(
                model_type=trained_model.model_type,
                message=f"Prediction failed: {str(e)}",
                details={"error": str(e)}
            )

    def predict_batch(
            self,
            model_uuid: str,
            datasource,
            sql: str,
            bind_variables: Optional[Dict[str, Any]] = None,
            _batch_size: int = 1000
    ) -> Dict[str, Any]:
        """
        Make batch predictions on data from datasource.

        Args:
            model_uuid: UUID of the trained model
            datasource: Datasource to fetch data from
            sql: SQL query for input data
            bind_variables: Query bind variables
            _batch_size: Batch size for predictions (reserved for future use)

        Returns:
            Dictionary with prediction statistics
        """
        # Load model
        trained_model = self.db_storage.load_supervised_model(model_uuid)

        if not trained_model:
            raise ModelNotFoundError(
                model_type=model_uuid,
                message=f"Model with UUID {model_uuid} not found",
                available_models=[]
            )

        # Fetch data
        data_result = datasource.fetch(sql, bind_variables or {})
        df = data_result.dataframe

        # Validate features
        required_features = trained_model.feature_columns or []
        missing_features = [f for f in required_features if f not in df.columns]

        if missing_features:
            raise ValidationError(
                message="Missing required features in query results",
                field_errors={
                    "sql": [
                        f"Query must return columns: {', '.join(required_features)}. "
                        f"Missing: {', '.join(missing_features)}"
                    ]
                }
            )

        # Make predictions in batches
        if required_features:
            X = df.select(required_features).to_pandas()
        else:
            X = df.to_pandas()

        predictions = trained_model.model.predict(X)

        return {
            "model_uuid": model_uuid,
            "model_name": trained_model.model_name,
            "total_predictions": len(predictions),
            "summary": {
                "min": float(predictions.min()) if trained_model.category == ModelCategory.REGRESSION else None,
                "max": float(predictions.max()) if trained_model.category == ModelCategory.REGRESSION else None,
                "mean": float(predictions.mean()) if trained_model.category == ModelCategory.REGRESSION else None,
            } if trained_model.category == ModelCategory.REGRESSION else {
                "class_distribution": {
                    str(k): int(v) for k, v in zip(*pl.Series(predictions).value_counts().to_numpy())
                }
            },
            "predictions": predictions.tolist()[:100]  # Return first 100 as sample
        }

    def get_model_info(self, model_uuid: str) -> Dict[str, Any]:
        """
        Get metadata for a trained model.

        Args:
            model_uuid: Model UUID

        Returns:
            Model metadata
        """
        metadata = self.db_storage.get_supervised_model_metadata(model_uuid)

        if not metadata:
            raise ModelNotFoundError(
                model_type=model_uuid,
                message=f"Model with UUID {model_uuid} not found",
                available_models=[]
            )

        return metadata
