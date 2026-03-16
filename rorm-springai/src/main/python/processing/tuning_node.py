"""Hyperparameter tuning pipeline node using Optuna."""

import asyncio
import logging
from datetime import datetime
from typing import Optional, Dict, Any, Callable

try:
    import optuna
    from optuna.pruners import MedianPruner
    from optuna.samplers import TPESampler

    OPTUNA_AVAILABLE = True
except ImportError:
    OPTUNA_AVAILABLE = False

from .pipeline_node import PipelineNode, StreamMessage
from dto.requests import TrainingRequest, SQLDatasourceConfig
from events.publisher import EventPublisher
from service.training_service import TrainingService
from core.exceptions import MLTrainingError
from datasource.throttler import AsyncThrottlerWrapper

logger = logging.getLogger(__name__)


class HyperparameterTuningNode(PipelineNode):
    """
    Pipeline node for hyperparameter tuning using Optuna.

    Consumes tuning requests with parameter spaces, runs optimization,
    and forwards best parameters to training node.
    """

    def __init__(
            self,
            redis_url: str,
            input_stream: str,
            output_stream: str,
            training_service: TrainingService,
            datasource_factory,
            event_publisher: EventPublisher,
            throttler: AsyncThrottlerWrapper,
            system_max_tuning_time: int = 300,
            consumer_group: str = "tuning_workers",
            consumer_name: Optional[str] = None
    ):
        """
        Initialize tuning pipeline node.

        Args:
            redis_url: Redis connection URL
            input_stream: Input stream for tuning requests
            output_stream: Output stream to forward best parameters
            training_service: Training service instance
            datasource_factory: Factory function to create datasource
            event_publisher: Event publisher for progress updates
            throttler: Async throttler wrapper for concurrency control
            system_max_tuning_time: System-wide max tuning time in seconds
            consumer_group: Consumer group name
            consumer_name: Unique consumer name
        """
        if not OPTUNA_AVAILABLE:
            raise ImportError(
                "Optuna is required for hyperparameter tuning. "
                "Install it with: pip install optuna"
            )

        super().__init__(
            redis_url=redis_url,
            input_streams=[input_stream],
            output_stream=output_stream,
            consumer_group=consumer_group,
            consumer_name=consumer_name,
            batch_size=10,  # Read multiple messages to enable concurrent processing
            retry_on_error=True,
            max_retries=1  # Less retries for tuning (expensive)
        )

        self.training_service = training_service
        self.datasource_factory = datasource_factory
        self.event_publisher = event_publisher
        self.throttler = throttler
        self.system_max_tuning_time = system_max_tuning_time

        # Optuna logging
        optuna.logging.set_verbosity(optuna.logging.WARNING)

    async def process(self, message: StreamMessage) -> Optional[StreamMessage]:
        """
        Process tuning request message.

        Args:
            message: Tuning request with parameter space

        Returns:
            Message with best parameters to forward to training node

        Raises:
            MLTrainingError: If tuning fails
        """
        training_id = message.payload.get("training_id")
        request_data = message.payload.get("request_data")
        param_space = message.payload.get("param_space")
        tuning_config = message.payload.get("tuning_config", {})

        if not training_id or not request_data or not param_space:
            raise ValueError("Invalid message: missing training_id, request_data, or param_space")

        # Calculate tuning time limit
        request_time_limit = tuning_config.get("max_tuning_time")
        time_limit = min(
            request_time_limit if request_time_limit else self.system_max_tuning_time,
            self.system_max_tuning_time
        )

        n_trials = tuning_config.get("n_trials", 50)
        optimization_metric = tuning_config.get("metric", "auto")

        logger.info(
            f"Starting hyperparameter tuning for {training_id}: "
            f"{n_trials} trials, {time_limit}s time limit"
        )

        # Publish tuning started
        await self.event_publisher.publish_progress(
            job_id=training_id,
            progress=0.0,
            message=f"Starting hyperparameter tuning with {n_trials} trials"
        )

        # Run optimization
        try:
            best_params = await self._run_optimization(
                training_id=training_id,
                request_data=request_data,
                param_space=param_space,
                n_trials=n_trials,
                time_limit=time_limit,
                optimization_metric=optimization_metric
            )

            # Publish tuning completed
            await self.event_publisher.publish_progress(
                job_id=training_id,
                progress=100.0,
                message=f"Tuning completed, best parameters found"
            )

            # Create message with best parameters for training node
            request_data["model_params"] = best_params
            request_data["tuning_metadata"] = {
                "tuned": True,
                "n_trials": n_trials,
                "time_limit": time_limit,
                "best_params": best_params
            }

            return StreamMessage(
                message_id=f"{training_id}_tuned",
                message_type="training_request",
                payload={
                    "training_id": training_id,
                    "request_data": request_data
                },
                metadata={
                    **message.metadata,
                    "tuned": True,
                    "tuning_completed_at": datetime.now().isoformat()
                }
            )

        except Exception as e:
            logger.exception(f"Tuning failed for {training_id}: {e}")
            raise MLTrainingError(
                message=f"Hyperparameter tuning failed: {str(e)}",
                error_code="TUNING_ERROR",
                details={"error": str(e)}
            )

    async def _run_optimization(
            self,
            training_id: str,
            request_data: Dict[str, Any],
            param_space: Dict[str, Any],
            n_trials: int,
            time_limit: int,
            optimization_metric: str
    ) -> Dict[str, Any]:
        """
        Run Optuna optimization.

        Args:
            training_id: Training identifier
            request_data: Base training request data
            param_space: Parameter space definition
            n_trials: Number of trials to run
            time_limit: Time limit in seconds
            optimization_metric: Metric to optimize

        Returns:
            Best parameters found
        """
        # Reconstruct base training request
        # Normalize empty feature_columns list to None (meaning "use all columns")
        feature_columns = request_data.get("feature_columns")
        if feature_columns is not None and len(feature_columns) == 0:
            feature_columns = None

        base_request = TrainingRequest(
            model_type=request_data["model_type"],
            model_name=request_data["model_name"],
            datasource=SQLDatasourceConfig(**request_data["datasource"]),
            target_column=request_data.get("target_column"),
            feature_columns=feature_columns,
            model_params={}  # Will be filled by Optuna
        )

        # Determine optimization direction
        model_category = self.training_service.registry.get(base_request.model_type).category.value
        direction = self._get_optimization_direction(model_category, optimization_metric)

        # Progress tracking
        trials_completed = 0

        async def progress_callback(trial_number: int, value: float):
            nonlocal trials_completed
            trials_completed = trial_number
            progress = (trial_number / n_trials) * 100
            await self.event_publisher.publish_progress(
                job_id=training_id,
                progress=progress,
                message=f"Tuning trial {trial_number}/{n_trials}, best value: {value:.4f}"
            )

        # Create objective function
        def objective(trial: optuna.Trial) -> float:
            # Sample parameters from space
            params = self._sample_params(trial, param_space)

            # Train model with these parameters
            request = TrainingRequest(
                model_type=base_request.model_type,
                model_name=f"{base_request.model_name}_trial_{trial.number}",
                datasource=base_request.datasource,
                target_column=base_request.target_column,
                feature_columns=base_request.feature_columns,
                model_params=params
            )

            # Run training synchronously (Optuna requires sync)
            datasource = self.datasource_factory()
            response = self.training_service.train(request, datasource)

            if not response.success:
                raise optuna.TrialPruned(f"Training failed: {response.message}")

            # Extract metric value
            metric_value = self._extract_metric(response.metrics, model_category, optimization_metric)

            # Async progress update (schedule it)
            try:
                loop = asyncio.get_event_loop()
                if loop.is_running():
                    asyncio.create_task(progress_callback(trial.number + 1, metric_value))
            except Exception:
                pass

            return metric_value

        # Run optimization in executor (Optuna is sync)
        loop = asyncio.get_event_loop()

        def run_study():
            study = optuna.create_study(
                direction=direction,
                sampler=TPESampler(seed=42),
                pruner=MedianPruner(n_startup_trials=5, n_warmup_steps=10)
            )

            study.optimize(
                objective,
                n_trials=n_trials,
                timeout=time_limit,
                show_progress_bar=False
            )

            return study.best_params

        best_params = await loop.run_in_executor(None, run_study)

        logger.info(f"Tuning completed for {training_id}, best params: {best_params}")
        return best_params

    def _sample_params(self, trial: optuna.Trial, param_space: Dict[str, Any]) -> Dict[str, Any]:
        """
        Sample parameters from space using Optuna trial.

        Param space format:
        {
            "param_name": {
                "type": "int" | "float" | "categorical" | "loguniform",
                "low": value,  # for int/float
                "high": value,  # for int/float
                "choices": [...],  # for categorical
                "log": bool  # for float
            }
        }
        """
        params = {}

        for param_name, param_config in param_space.items():
            param_type = param_config.get("type", "float")

            if param_type == "int":
                params[param_name] = trial.suggest_int(
                    param_name,
                    param_config["low"],
                    param_config["high"],
                    log=param_config.get("log", False)
                )
            elif param_type == "float":
                params[param_name] = trial.suggest_float(
                    param_name,
                    param_config["low"],
                    param_config["high"],
                    log=param_config.get("log", False)
                )
            elif param_type == "loguniform":
                params[param_name] = trial.suggest_float(
                    param_name,
                    param_config["low"],
                    param_config["high"],
                    log=True
                )
            elif param_type == "categorical":
                params[param_name] = trial.suggest_categorical(
                    param_name,
                    param_config["choices"]
                )
            else:
                logger.warning(f"Unknown parameter type '{param_type}' for {param_name}, skipping")

        return params

    def _get_optimization_direction(self, model_category: str, metric: str) -> str:
        """
        Determine optimization direction (minimize or maximize).

        Args:
            model_category: Model category
            metric: Metric to optimize (or "auto")

        Returns:
            "minimize" or "maximize"
        """
        if metric == "auto":
            # Default metrics by category
            if model_category in ["regression"]:
                return "minimize"  # MSE, MAE
            elif model_category in ["classification"]:
                return "maximize"  # Accuracy, F1
            elif model_category in ["clustering"]:
                return "maximize"  # Silhouette score
            else:
                return "minimize"

        # Metrics that should be minimized
        minimize_metrics = ["mse", "rmse", "mae", "loss", "error", "davies_bouldin"]

        # Metrics that should be maximized
        maximize_metrics = ["accuracy", "f1", "precision", "recall", "r2", "silhouette", "auc", "roc_auc"]

        metric_lower = metric.lower()
        if any(m in metric_lower for m in minimize_metrics):
            return "minimize"
        elif any(m in metric_lower for m in maximize_metrics):
            return "maximize"
        else:
            logger.warning(f"Unknown metric '{metric}', defaulting to minimize")
            return "minimize"

    def _extract_metric(
            self,
            metrics: Optional[Dict[str, Any]],
            model_category: str,
            metric_name: str
    ) -> float:
        """
        Extract metric value from training response.

        Args:
            metrics: Training metrics dictionary
            model_category: Model category
            metric_name: Metric to extract (or "auto")

        Returns:
            Metric value as float

        Raises:
            ValueError: If metric not found
        """
        if not metrics:
            raise ValueError("No metrics returned from training")

        if metric_name == "auto":
            # Choose default metric by category
            if model_category == "regression":
                metric_name = "mse"
            elif model_category == "classification":
                metric_name = "f1_score"
            elif model_category == "clustering":
                metric_name = "silhouette_score"
            elif model_category == "causal":
                metric_name = "absolute_effect"
            else:
                raise ValueError(f"No default metric for category '{model_category}'")

        # Try to find metric (case-insensitive)
        for key, value in metrics.items():
            if key.lower() == metric_name.lower():
                if isinstance(value, (int, float)):
                    return float(value)
                else:
                    raise ValueError(f"Metric '{metric_name}' is not numeric: {value}")

        raise ValueError(
            f"Metric '{metric_name}' not found in training results. "
            f"Available metrics: {list(metrics.keys())}"
        )

    async def on_permanent_failure(self, message: StreamMessage, error: Exception):
        """Handle permanent tuning failure."""
        training_id = message.payload.get("training_id")
        if training_id:
            await self.event_publisher.publish_failed(
                job_id=training_id,
                error=str(error),
                error_code="TUNING_FAILED",
                message=f"Hyperparameter tuning failed permanently: {str(error)}"
            )
