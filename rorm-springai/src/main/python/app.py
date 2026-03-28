"""
FastAPI application using pure dependency injection.

All components are instantiated through the DI container.
No manual instantiation or global state.
"""

import asyncio
import json
import logging
import uuid
from contextlib import asynccontextmanager
from datetime import datetime
from dependency_injector.wiring import inject, Provide
from fastapi import FastAPI, Depends, HTTPException, Query
from typing import Optional

from config.settings import Settings
from core.container import (ApplicationContainer)
from dto import TrainingRequest, StabilitySelectionRequest
from service.prediction_service import PredictionService

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s - %(name)s - %(levelname)s - %(message)s"
)
logger = logging.getLogger(__name__)


# ========== Application Lifespan ==========


@asynccontextmanager
async def lifespan(_: FastAPI):
    """Manage application lifecycle with DI container."""
    # Initialize container
    container = ApplicationContainer()
    container.wire(modules=[__name__])

    logger.info("Starting ML Training Service")
    logger.info("DI container initialized")

    # Start pipeline nodes
    training_node = container.training_node()
    tuning_node = container.tuning_node()
    stability_selection_node = container.stability_selection_node()
    shap_node = container.shap_node()
    causal_verification_node = container.causal_verification_node()

    training_task = asyncio.create_task(training_node.start())
    tuning_task = asyncio.create_task(tuning_node.start())
    stability_selection_task = asyncio.create_task(stability_selection_node.start())
    shap_task = asyncio.create_task(shap_node.start())
    causal_verification_task = asyncio.create_task(causal_verification_node.start())

    logger.info("Pipeline nodes started")

    try:
        yield {"container": container}
    finally:
        # Cleanup
        logger.info("Shutting down ML Training Service")

        await training_node.stop()
        await tuning_node.stop()
        await stability_selection_node.stop()
        await shap_node.stop()
        await causal_verification_node.stop()

        training_task.cancel()
        tuning_task.cancel()
        stability_selection_task.cancel()
        shap_task.cancel()
        causal_verification_task.cancel()

        try:
            await asyncio.gather(
                training_task,
                tuning_task,
                stability_selection_task,
                shap_task,
                causal_verification_task,
                return_exceptions=True,
            )
        except asyncio.CancelledError:
            pass

        container.worker_pool().shutdown(wait=True)

        await container.shutdown_resources()
        logger.info("Service stopped")


# ========== FastAPI Application ==========


def create_app() -> FastAPI:
    """Create and configure FastAPI application."""
    app = FastAPI(
        title="ML Training Service",
        description="Distributed ML model training with hyperparameter tuning",
        version="2.0.0",
        lifespan=lifespan
    )

    # Add routes
    _add_training_routes(app)
    _add_prediction_routes(app)
    _add_unsupervised_routes(app)
    _add_analysis_routes(app)
    _add_causal_verification_routes(app)
    _add_info_routes(app)

    return app


# ========== Training Routes ==========


def _add_training_routes(app: FastAPI):
    """Add training-related routes."""

    @app.post("/train", response_model=dict, tags=["Training"])
    @inject
    async def train(
            request: TrainingRequest,
            event_publisher=Depends(Provide[ApplicationContainer.event_publisher]),
            config: Settings = Depends(Provide[ApplicationContainer.config])
    ):
        """
        Train ML model (async via processing).

        Returns training_id immediately. Monitor progress via events
        on channels: ml_training.events and ml_training.<training_id>
        """
        training_id = str(uuid.uuid4())

        # Publish to training processing
        request_data = {
            "model_type": request.model_type,
            "model_name": request.model_name,
            "datasource": {
                "sql": request.datasource.sql,
                "bind_variables": request.datasource.bind_variables or {}
            },
            "target_column": request.target_column,
            "feature_columns": request.feature_columns,
            "model_params": request.model_params or {}
        }

        await event_publisher.add_to_stream(
            config.pipeline.streams.training_requests,
            {
                "message_type": "training_request",
                "payload": json.dumps({
                    "training_id": training_id,
                    "request_data": request_data
                }),
                "metadata": json.dumps({}),
                "timestamp": datetime.now().isoformat(),
                "retry_count": "0"
            }
        )

        return {
            "status": "accepted",
            "training_id": training_id,
            "message": "Training request queued successfully"
        }

    @app.post("/tune-and-train", response_model=dict, tags=["Training"])
    @inject
    async def tune_and_train(
            request: TrainingRequest,
            param_space: dict,
            tuning_config: dict | None = None,
            event_publisher=Depends(Provide[ApplicationContainer.event_publisher]),
            config: Settings = Depends(Provide[ApplicationContainer.config])
    ):
        """
        Tune hyperparameters and train model.

        Param space format:
        {
            "param_name": {
                "type": "int"|"float"|"categorical"|"loguniform",
                "low": value, "high": value,  # for int/float
                "choices": [...]  # for categorical
            }
        }

        Tuning config:
        {
            "n_trials": 50,
            "max_tuning_time": 300,
            "metric": "auto"
        }
        """
        training_id = str(uuid.uuid4())

        # Publish to tuning processing
        request_data = {
            "model_type": request.model_type,
            "model_name": request.model_name,
            "datasource": {
                "sql": request.datasource.sql,
                "bind_variables": request.datasource.bind_variables or {}
            },
            "target_column": request.target_column,
            "feature_columns": request.feature_columns
        }

        await event_publisher.add_to_stream(
            config.pipeline.streams.tuning_requests,
            {
                "message_type": "tuning_request",
                "payload": json.dumps({
                    "training_id": training_id,
                    "request_data": request_data,
                    "param_space": param_space,
                    "tuning_config": tuning_config or {}
                }),
                "metadata": json.dumps({}),
                "timestamp": datetime.now().isoformat(),
                "retry_count": "0"
            }
        )

        return {
            "status": "accepted",
            "training_id": training_id,
            "message": "Hyperparameter tuning request queued successfully"
        }


# ========== Prediction Routes ==========


def _add_prediction_routes(app: FastAPI):
    """Add prediction-related routes."""

    @app.post("/predict/{model_uuid}", response_model=dict, tags=["Prediction"])
    @inject
    async def predict(
            model_uuid: str,
            input_data: dict | list[dict],
            prediction_service: PredictionService = Depends(Provide[ApplicationContainer.prediction_service])
    ):
        """Make predictions using trained model."""
        return prediction_service.predict(model_uuid, input_data)

    @app.get("/models/supervised", response_model=list, tags=["Models"])
    @inject
    async def list_models(
            db_storage=Depends(Provide[ApplicationContainer.db_storage])
    ):
        """List all trained supervised models."""
        return db_storage.list_supervised_models()

    @app.get("/models/supervised/{model_uuid}", response_model=dict, tags=["Models"])
    @inject
    async def get_model_info(
            model_uuid: str,
            prediction_service: PredictionService = Depends(Provide[ApplicationContainer.prediction_service])
    ):
        """Get model metadata."""
        return prediction_service.get_model_info(model_uuid)

    @app.delete("/models/supervised/{model_uuid}", response_model=dict, tags=["Models"])
    @inject
    async def delete_model(
            model_uuid: str,
            db_storage=Depends(Provide[ApplicationContainer.db_storage])
    ):
        """Delete trained model."""
        deleted = db_storage.delete_supervised_model(model_uuid)
        return {
            "deleted": deleted,
            "model_uuid": model_uuid
        }


# ========== Unsupervised Results Routes ==========


def _add_unsupervised_routes(app: FastAPI):
    """Add unsupervised model results routes."""

    @app.get("/results/unsupervised", response_model=list, tags=["Unsupervised Results"])
    @inject
    async def list_unsupervised_results(
            limit: int = 100,
            db_storage=Depends(Provide[ApplicationContainer.db_storage])
    ):
        """
        List all unsupervised model results.

        Returns a list of result tables with metadata including:
        - results_uuid: Unique identifier for the results
        - table_name: Database table name
        - row_count: Number of rows in the results
        - size: Table size on disk
        - created_at: When the results were created
        """
        return db_storage.list_unsupervised_results(limit=limit)

    @app.get("/results/unsupervised/{results_uuid}", response_model=dict, tags=["Unsupervised Results"])
    @inject
    async def get_unsupervised_results(
            results_uuid: str,
            limit: int = 1000,
            offset: int = 0,
            db_storage=Depends(Provide[ApplicationContainer.db_storage])
    ):
        """
        Get unsupervised model results by UUID.

        Args:
            results_uuid: UUID of the results table
            limit: Maximum number of rows to return (default: 1000, max: 10000)
            offset: Number of rows to skip for pagination (default: 0)

        Returns:
            Dictionary containing:
            - results_uuid: The results UUID
            - total_rows: Total number of rows in the table
            - returned_rows: Number of rows in this response
            - limit: The limit used
            - offset: The offset used
            - data: Array of result rows
        """
        if limit > 10000:
            limit = 10000

        results = db_storage.get_unsupervised_results(
            results_uuid=results_uuid,
            limit=limit,
            offset=offset
        )

        if results is None:
            raise HTTPException(
                status_code=404,
                detail=f"Unsupervised results not found for UUID: {results_uuid}"
            )

        return results

    @app.delete("/results/unsupervised/{results_uuid}", response_model=dict, tags=["Unsupervised Results"])
    @inject
    async def delete_unsupervised_results(
            results_uuid: str,
            db_storage=Depends(Provide[ApplicationContainer.db_storage])
    ):
        """
        Delete unsupervised model results.

        This will drop the entire results table from the database.
        This action cannot be undone.
        """
        deleted = db_storage.delete_unsupervised_results(results_uuid)

        if not deleted:
            raise HTTPException(
                status_code=404,
                detail=f"Unsupervised results not found for UUID: {results_uuid}"
            )

        return {
            "deleted": True,
            "results_uuid": results_uuid,
            "message": "Unsupervised results deleted successfully"
        }


# ========== Analysis Routes ==========


def _add_analysis_routes(app: FastAPI):
    """Add analysis routes."""

    @app.post("/analysis/stability-selection", response_model=dict, tags=["Analysis"])
    @inject
    async def stability_selection(
            request: StabilitySelectionRequest,
            stability_selection_service=Depends(Provide[ApplicationContainer.stability_selection_service]),
            datasource=Depends(Provide[ApplicationContainer.datasource]),
    ):
        """Run stability selection analysis across multiple model families."""
        return stability_selection_service.analyze(request, datasource)

    @app.get("/analysis/stability-selection/runs", response_model=list, tags=["Analysis"])
    @inject
    async def list_stability_runs(
            limit: int = 100,
            db_storage=Depends(Provide[ApplicationContainer.db_storage]),
    ):
        """List stored stability selection runs."""
        return db_storage.list_stability_runs(limit=limit)

    @app.get("/analysis/stability-selection/{run_id}/shap-curves", response_model=dict, tags=["Analysis"])
    @inject
    async def shap_curves(
            run_id: str,
            features: Optional[str] = Query(None, description="Comma-separated feature names"),
            n_bins: int = Query(100, ge=10, le=1000),
            n_breakpoints: int = Query(1, ge=1, le=5),
            subsample: bool = Query(False, description="Subsample rows for faster SHAP on large datasets"),
            shap_curve_service=Depends(Provide[ApplicationContainer.shap_curve_service]),
    ):
        """Compute averaged SHAP dependence curves for a stability selection run."""
        feature_list = [f.strip() for f in features.split(",")] if features else None

        loop = asyncio.get_event_loop()
        result = await loop.run_in_executor(
            None,
            lambda: shap_curve_service.compute_curves(
                run_id=run_id,
                features=feature_list,
                n_bins=n_bins,
                n_breakpoints=n_breakpoints,
                subsample=subsample,
            ),
        )

        if result is None:
            raise HTTPException(status_code=404, detail=f"Stability run not found: {run_id}")

        return result

    @app.post("/analysis/stability-selection/{run_id}/shap-curves/async", response_model=dict, tags=["Analysis"])
    @inject
    async def shap_curves_async(
            run_id: str,
            features: Optional[str] = Query(None, description="Comma-separated feature names"),
            n_bins: int = Query(100, ge=10, le=1000),
            n_breakpoints: int = Query(1, ge=1, le=5),
            event_publisher=Depends(Provide[ApplicationContainer.event_publisher]),
            config: Settings = Depends(Provide[ApplicationContainer.config]),
    ):
        """
        Queue async SHAP curve computation for a stability selection run.

        Returns analysis_id immediately. Monitor progress via the same event
        channels used by training: ml_training.events and ml_training.<analysis_id>
        """
        analysis_id = str(uuid.uuid4())
        feature_list = [f.strip() for f in features.split(",")] if features else None

        await event_publisher.add_to_stream(
            config.pipeline.streams.shap_requests,
            {
                "message_type": "shap_request",
                "payload": json.dumps({
                    "analysis_id": analysis_id,
                    "request_data": {
                        "run_id": run_id,
                        "features": feature_list,
                        "n_bins": n_bins,
                        "n_breakpoints": n_breakpoints,
                    },
                }),
                "metadata": json.dumps({}),
                "timestamp": datetime.now().isoformat(),
                "retry_count": "0",
            }
        )

        return {
            "status": "accepted",
            "analysis_id": analysis_id,
            "message": "SHAP curve computation request queued successfully",
        }

    @app.delete("/analysis/stability-selection/{run_id}", response_model=dict, tags=["Analysis"])
    @inject
    async def delete_stability_run(
            run_id: str,
            db_storage=Depends(Provide[ApplicationContainer.db_storage]),
    ):
        """Delete a stability selection run and its models."""
        deleted = db_storage.delete_stability_run(run_id)
        if not deleted:
            raise HTTPException(status_code=404, detail=f"Stability run not found: {run_id}")
        return {"deleted": True, "run_id": run_id}

    @app.post("/analysis/stability-selection/async", response_model=dict, tags=["Analysis"])
    @inject
    async def stability_selection_async(
            request: StabilitySelectionRequest,
            event_publisher=Depends(Provide[ApplicationContainer.event_publisher]),
            config: Settings = Depends(Provide[ApplicationContainer.config]),
    ):
        """
        Queue async stability selection analysis.

        Returns analysis_id immediately. Monitor progress via the same event
        channels used by training: ml_training.events and ml_training.<analysis_id>
        """
        analysis_id = str(uuid.uuid4())

        await event_publisher.add_to_stream(
            config.pipeline.streams.stability_selection_requests,
            {
                "message_type": "stability_selection_request",
                "payload": json.dumps({
                    "analysis_id": analysis_id,
                    "request_data": request.to_dict(),
                }),
                "metadata": json.dumps({}),
                "timestamp": datetime.now().isoformat(),
                "retry_count": "0",
            }
        )

        return {
            "status": "accepted",
            "analysis_id": analysis_id,
            "message": "Stability selection request queued successfully",
        }


# ========== Causal Verification Routes ==========


def _add_causal_verification_routes(app: FastAPI):
    """Add causal verification pipeline routes."""

    @app.post("/analysis/causal-verification", response_model=dict, tags=["Causal Verification"])
    @inject
    async def causal_verification_sync(
            request: dict,
            causal_verification_service=Depends(Provide[ApplicationContainer.causal_verification_service]),
            datasource=Depends(Provide[ApplicationContainer.datasource]),
            config: Settings = Depends(Provide[ApplicationContainer.config]),
    ):
        """
        Run the full causal verification pipeline synchronously.

        Accepts a PipelineSpec JSON body and returns the complete pipeline result.
        Steps are checkpointed — a retry with the same hypothesis_id resumes
        from the last completed step.
        Use the async variant for long-running pipelines.
        """
        import asyncio
        from dto.causal_verification_request import CausalVerificationRequest
        from service.pipeline_checkpoint import PipelineCheckpoint

        spec = CausalVerificationRequest.from_dict(request)
        run_id = spec.hypothesis_id
        checkpoint = PipelineCheckpoint(run_id=run_id, redis_url=config.redis.get_url())

        loop = asyncio.get_event_loop()
        result = await loop.run_in_executor(
            None,
            lambda: causal_verification_service.run_pipeline(
                spec, datasource, checkpoint=checkpoint,
            ),
        )
        return result

    @app.post("/analysis/causal-verification/async", response_model=dict, tags=["Causal Verification"])
    @inject
    async def causal_verification_async(
            request: dict,
            event_publisher=Depends(Provide[ApplicationContainer.event_publisher]),
            config: Settings = Depends(Provide[ApplicationContainer.config]),
    ):
        """
        Queue async causal verification pipeline execution.

        Returns analysis_id immediately. Monitor progress via event
        channels: ml_training.events and ml_training.<analysis_id>
        """
        analysis_id = str(uuid.uuid4())

        await event_publisher.add_to_stream(
            config.pipeline.streams.causal_verification_requests,
            {
                "message_type": "causal_verification_request",
                "payload": json.dumps({
                    "analysis_id": analysis_id,
                    "request_data": request,
                }),
                "metadata": json.dumps({}),
                "timestamp": datetime.now().isoformat(),
                "retry_count": "0",
            }
        )

        return {
            "status": "accepted",
            "analysis_id": analysis_id,
            "message": "Causal verification pipeline request queued successfully",
        }

    @app.post(
        "/analysis/causal-verification/runs/{run_id}/reexecute",
        response_model=dict,
        tags=["Causal Verification"],
    )
    @inject
    async def reexecute_sync(
            run_id: str,
            spec_patch: dict,
            reexecution_engine=Depends(Provide[ApplicationContainer.reexecution_engine]),
            datasource=Depends(Provide[ApplicationContainer.datasource]),
    ):
        """
        Re-execute a completed run with a partial spec change.

        Only the steps affected by the changed fields are re-computed.
        Returns the new run_id, per-step diffs, and the full result.
        Both the base run and the new run are frozen on completion.
        """
        import asyncio

        loop = asyncio.get_event_loop()
        return await loop.run_in_executor(
            None,
            lambda: reexecution_engine.reexecute(
                base_run_id=run_id,
                spec_patch=spec_patch,
                datasource=datasource,
            ),
        )

    @app.get(
        "/analysis/causal-verification/runs",
        response_model=list,
        tags=["Causal Verification"],
    )
    @inject
    async def list_causal_runs(
            limit: int = Query(50, ge=1, le=500),
            config: Settings = Depends(Provide[ApplicationContainer.config]),
    ):
        """List completed causal verification runs (most recent first)."""
        from service.pipeline_checkpoint import PipelineCheckpoint
        return PipelineCheckpoint.list_runs(config.redis.get_url(), limit=limit)

    @app.get(
        "/analysis/causal-verification/runs/{run_id}",
        response_model=dict,
        tags=["Causal Verification"],
    )
    @inject
    async def get_causal_run(
            run_id: str,
            config: Settings = Depends(Provide[ApplicationContainer.config]),
    ):
        """Get metadata and result for a specific run."""
        from service.pipeline_checkpoint import PipelineCheckpoint
        cp = PipelineCheckpoint(run_id, config.redis.get_url())
        meta = cp.load_run_meta()
        if meta is None:
            raise HTTPException(status_code=404, detail=f"Run not found: {run_id}")
        meta["run_id"] = run_id
        return meta

    @app.get(
        "/analysis/causal-verification/dag",
        response_model=dict,
        tags=["Causal Verification"],
    )
    async def get_pipeline_dag():
        """Return the static dependency DAG used for re-execution invalidation."""
        from service.reexecution_engine import PIPELINE_DAG, STEP_ORDER
        return {
            "step_order": STEP_ORDER,
            "steps": {
                name: {
                    "spec_inputs": sorted(defn.spec_inputs),
                    "step_inputs": sorted(defn.step_inputs),
                }
                for name, defn in PIPELINE_DAG.items()
            },
        }


# ========== Info Routes ==========


def _add_info_routes(app: FastAPI):
    """Add informational routes."""

    @app.get("/", tags=["Info"])
    async def root():
        """Service information."""
        return {
            "service": "ML Training Service",
            "version": "2.0.0",
            "status": "running",
            "architecture": "pipeline-based with DI"
        }

    @app.get("/models", response_model=list, tags=["Info"])
    @inject
    async def list_available_models(
            registry=Depends(Provide[ApplicationContainer.model_registry])
    ):
        """List all available model types."""
        return registry.list_models()

    @app.get("/health", tags=["Info"])
    async def health_check():
        """Health check endpoint."""
        return {"status": "healthy"}


# ========== Application Instance ==========


app = create_app()
