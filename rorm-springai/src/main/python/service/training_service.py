"""
Training Service - Main orchestrator for model training.

Coordinates data fetching, validation, training, and storage.
"""

import logging
import polars as pl
from core.exceptions import (
    MLTrainingError,
    ValidationError,
    ModelTrainingError,
)
from datasource.interface import Datasource
from dto.requests import TrainingRequest
from dto.responses import TrainingResponse, ModelMetadata
from models.registry import ModelTrainerRegistry
from storage.file_storage import FileModelStorage
from typing import Any, Optional, Callable

logger = logging.getLogger(__name__)


class TrainingService:
    """
    Main service for training ML models.

    Orchestrates the complete training workflow:
    1. Request validation
    2. Model discovery via registry
    3. Data fetching from datasource
    4. Model training
    5. Model persistence
    """

    def __init__(
            self,
            registry: ModelTrainerRegistry,
            storage: FileModelStorage,
            db_storage=None,
    ):
        """
        Initialize training service.

        Args:
            registry: Model trainer registry for discovering trainers
            storage: Model storage for persisting trained models
            db_storage: Optional database storage for supervised models and unsupervised results
        """
        self._registry = registry
        self._storage = storage
        self._db_storage = db_storage

    @property
    def registry(self) -> ModelTrainerRegistry:
        """Get the model trainer registry."""
        return self._registry

    def train(
            self,
            request: TrainingRequest,
            datasource: Datasource,
            progress_callback: Optional[Callable[[float, str], None]] = None,
    ) -> TrainingResponse:
        """
        Train a model based on the request.

        Args:
            request: Training request with all parameters
            datasource: Data source for fetching training data
            progress_callback: Optional callback for progress updates (progress: float, message: str)

        Returns:
            TrainingResponse with result or error details
        """
        try:
            # Step 1: Validate request
            logger.info(f"Validating training request for model '{request.model_name}'")
            available_models = self._registry.list_models()
            validation_result = request.validate(available_models)
            validation_result.raise_if_invalid()

            if progress_callback:
                progress_callback(0.1, "Validation complete")

            # Step 2: Get trainer from registry
            logger.info(f"Discovering trainer for model type '{request.model_type}'")
            trainer = self._registry.get(request.model_type)

            # Step 3: Validate model-specific parameters
            if request.model_params:
                param_validation = trainer.validate_params(request.model_params)
                param_validation.raise_if_invalid()

            if progress_callback:
                progress_callback(0.2, "Model trainer initialized")

            # Step 4: Fetch data from datasource
            logger.info(f"Fetching training data from datasource")
            if progress_callback:
                progress_callback(0.25, "Fetching data from datasource")

            data_result = datasource.fetch(
                request.datasource.sql,
                request.datasource.bind_variables,
            )
            df = data_result.dataframe
            warnings = data_result.warnings.copy()

            logger.info(
                f"Fetched {data_result.row_count} rows with {data_result.column_count} columns"
            )

            if progress_callback:
                progress_callback(0.4, f"Data fetched: {data_result.row_count} rows")

            # Step 5: Validate data against trainer requirements
            self._validate_data_for_trainer(
                df, trainer, request.feature_columns, request.target_column
            )

            if progress_callback:
                progress_callback(0.5, "Data validation complete")

            # Step 6: Train model
            logger.info(f"Training {request.model_type} model '{request.model_name}'")
            if progress_callback:
                progress_callback(0.55, f"Starting model training")

            trained_model = trainer.fit(
                df=df,
                model_name=request.model_name,
                feature_columns=request.feature_columns,
                target_column=request.target_column,
                params=request.model_params,
            )

            if progress_callback:
                progress_callback(0.85, "Model training complete")

            # Step 7: Save model to storage
            logger.info(f"Saving trained model to storage")
            if progress_callback:
                progress_callback(0.9, "Saving model to storage")

            storage_path = self._storage.save(trained_model)

            # Step 8: Save to database if available
            model_uuid = None
            results_uuid = None

            if self._db_storage:
                from models.base import ModelCategory

                if trained_model.category in [ModelCategory.REGRESSION, ModelCategory.CLASSIFICATION]:
                    # Supervised/prediction model - save to DB for predictions
                    logger.info(f"Saving supervised model to database")
                    model_uuid = self._db_storage.save_supervised_model(trained_model)
                    logger.info(f"Model saved with UUID: {model_uuid}")

                elif trained_model.category in [ModelCategory.CLUSTERING, ModelCategory.DIMENSIONALITY,
                                                ModelCategory.ASSOCIATION]:
                    # Unsupervised model - save results to dynamic table
                    logger.info(f"Saving unsupervised results to database")

                    # Get transformed data or cluster assignments
                    if hasattr(trained_model.model, 'labels_'):
                        # Clustering models have labels_
                        results_df = df.with_columns(
                            pl.Series('cluster', trained_model.model.labels_)
                        )
                    elif hasattr(trained_model.model, 'transform'):
                        # Dimensionality reduction
                        transformed = trained_model.model.transform(
                            df.select(trained_model.feature_columns).to_pandas()
                        )
                        results_df = df.with_columns([
                            pl.Series(f'component_{i}', transformed[:, i])
                            for i in range(transformed.shape[1])
                        ])
                    elif hasattr(trained_model.model, 'rules'):
                        # Association rule mining (Apriori, FP-Growth, Eclat)
                        # Convert rules DataFrame to Polars
                        import pandas as pd
                        rules_pandas = trained_model.model.rules

                        if not rules_pandas.empty:
                            # Convert frozensets to strings for storage
                            rules_pandas = rules_pandas.copy()
                            rules_pandas['antecedents'] = rules_pandas['antecedents'].apply(
                                lambda x: ','.join(sorted(list(x)))
                            )
                            rules_pandas['consequents'] = rules_pandas['consequents'].apply(
                                lambda x: ','.join(sorted(list(x)))
                            )

                            # Select key columns
                            rules_pandas = rules_pandas[[
                                'antecedents', 'consequents', 'support',
                                'confidence', 'lift'
                            ]]

                            results_df = pl.from_pandas(rules_pandas)
                        else:
                            # No rules generated - save frequent itemsets instead
                            itemsets_pandas = trained_model.model.frequent_itemsets.copy()
                            itemsets_pandas['items'] = itemsets_pandas['itemsets'].apply(
                                lambda x: ','.join(sorted(list(x)))
                            )
                            itemsets_pandas = itemsets_pandas[['items', 'support']]
                            results_df = pl.from_pandas(itemsets_pandas)
                    else:
                        # Default: save original data with model metadata
                        results_df = df

                    results_uuid = self._db_storage.save_unsupervised_results(
                        trained_model,
                        results_df
                    )
                    logger.info(f"Results saved with UUID: {results_uuid}")

            if progress_callback:
                progress_callback(0.95, "Model saved successfully")

            # Step 9: Build response
            metadata = ModelMetadata(
                model_name=trained_model.model_name,
                model_type=trained_model.model_type,
                model_category=trained_model.category.value,
                feature_columns=trained_model.feature_columns,
                target_column=trained_model.target_column,
                training_rows=trained_model.training_rows,
                training_columns=trained_model.training_columns,
                model_params=trained_model.model_params,
                training_metrics=trained_model.training_metrics,
                created_at=trained_model.created_at,
                storage_path=storage_path,
                model_uuid=model_uuid,
                results_uuid=results_uuid,
            )

            logger.info(
                f"Successfully trained and saved model '{request.model_name}'"
            )
            return TrainingResponse.success_response(metadata, warnings)

        except MLTrainingError as e:
            logger.error(f"Training failed: {e}")
            return TrainingResponse.from_exception(e)
        except Exception as e:
            logger.exception(f"Unexpected error during training: {e}")
            return TrainingResponse.from_exception(e)

    def _validate_data_for_trainer(
            self,
            df: pl.DataFrame,
            trainer,
            feature_columns: Optional[list[str]],
            target_column: Optional[str],
    ) -> None:
        """Validate that data meets trainer requirements."""
        available_columns = df.columns

        # Check target column for supervised models
        if trainer.requires_target:
            if not target_column:
                raise ValidationError(
                    message=f"Target column is required for model type '{trainer.model_type}'",
                    field_errors={
                        "target_column": [
                            f"Model type '{trainer.model_type}' requires a target column for supervised learning"
                        ]
                    },
                )
            if target_column not in available_columns:
                raise ModelTrainingError.missing_target_column(
                    trainer.model_type, target_column, available_columns
                )

        # Check feature columns if specified
        if feature_columns:
            missing = [c for c in feature_columns if c not in available_columns]
            if missing:
                raise ValidationError(
                    message=f"Feature columns not found in dataset",
                    field_errors={
                        "feature_columns": [
                            f"Columns not found: {', '.join(missing)}. "
                            f"Available columns: {', '.join(available_columns)}"
                        ]
                    },
                )

        # Check minimum rows
        if df.height < trainer.minimum_rows:
            raise ModelTrainingError.insufficient_data(
                trainer.model_type, df.height, trainer.minimum_rows
            )

    def train_from_dict(
            self,
            request_dict: dict[str, Any],
            datasource: Datasource,
    ) -> TrainingResponse:
        """
        Train a model from a dictionary request.

        Convenience method for API integration.

        Args:
            request_dict: Dictionary containing request fields
            datasource: Data source for fetching training data

        Returns:
            TrainingResponse with result or error details
        """
        try:
            request = TrainingRequest.from_dict(request_dict)
            return self.train(request, datasource)
        except ValidationError as e:
            return TrainingResponse.from_exception(e)
        except Exception as e:
            return TrainingResponse.from_exception(e)

    def get_available_models(self) -> dict[str, Any]:
        """
        Get information about all available models.

        Returns:
            Dictionary with model information grouped by category
        """
        all_models = self._registry.get_all_models_info()

        # Group by category
        by_category: dict[str, list[dict]] = {}
        for name, info in all_models.items():
            category = info["category"]
            if category not in by_category:
                by_category[category] = []
            by_category[category].append({
                "name": name,
                "requires_target": info["requires_target"],
                "minimum_rows": info["minimum_rows"],
                "default_params": info["default_params"],
                "param_schema": info["param_schema"],
            })

        return {
            "total_models": len(all_models),
            "categories": list(by_category.keys()),
            "models_by_category": by_category,
        }

    def get_model_info(self, model_type: str) -> dict[str, Any]:
        """
        Get detailed information about a specific model type.

        Args:
            model_type: The model type identifier

        Returns:
            Dictionary with model information

        Raises:
            ModelNotFoundError: If model type not registered
        """
        return self._registry.get_model_info(model_type)

    def list_trained_models(self) -> list[dict[str, Any]]:
        """
        List all trained models in storage.

        Returns:
            List of model metadata dictionaries
        """
        return self._storage.list_models()

    def get_trained_model_metadata(self, model_name: str) -> Optional[dict[str, Any]]:
        """
        Get metadata for a trained model.

        Args:
            model_name: Name of the trained model

        Returns:
            Metadata dictionary or None if not found
        """
        return self._storage.get_metadata(model_name)

    def delete_trained_model(self, model_name: str) -> bool:
        """
        Delete a trained model from storage.

        Args:
            model_name: Name of the model to delete

        Returns:
            True if deleted, False if not found
        """
        return self._storage.delete(model_name)
