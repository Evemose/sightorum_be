"""Pipeline node for async SHAP curve computation."""

import asyncio
import logging
from events.publisher import EventPublisher
from service.shap_curve_service import ShapCurveService
from typing import Optional

from .pipeline_node import PipelineNode, StreamMessage

logger = logging.getLogger(__name__)


class ShapPipelineNode(PipelineNode):
    """
    Pipeline node for async SHAP dependence curve computation.

    Consumes requests from a Redis stream, runs SHAP computation in an executor,
    and publishes progress/result events using the same event channels as training.
    """

    def __init__(
            self,
            redis_url: str,
            input_stream: str,
            shap_curve_service: ShapCurveService,
            event_publisher: EventPublisher,
            output_stream: Optional[str] = None,
            consumer_group: str = "shap_workers",
            consumer_name: Optional[str] = None,
            worker_pool=None,
            backpressure=None,
    ):
        super().__init__(
            redis_url=redis_url,
            input_streams=[input_stream],
            output_stream=output_stream,
            consumer_group=consumer_group,
            consumer_name=consumer_name,
            batch_size=1,
            retry_on_error=True,
            max_retries=3,
            worker_pool=worker_pool,
            backpressure=backpressure,
        )

        self.shap_curve_service = shap_curve_service
        self.event_publisher = event_publisher

    async def process(self, message: StreamMessage) -> Optional[StreamMessage]:
        """Process one async SHAP curve request."""
        analysis_id = message.payload.get("analysis_id")
        request_data = message.payload.get("request_data")

        if not analysis_id or not request_data:
            raise ValueError("Invalid message format: missing analysis_id or request_data")

        run_id = request_data["run_id"]
        features = request_data.get("features")
        n_bins = request_data.get("n_bins", 100)
        n_breakpoints = request_data.get("n_breakpoints", 1)

        await self.event_publisher.publish_started(
            job_id=analysis_id,
            model_type="shap_curves",
            message=f"SHAP curve computation started for run: {run_id}",
        )

        async def progress_callback(progress: float, message: str = ""):
            await self.event_publisher.publish_progress(
                job_id=analysis_id, progress=progress, message=message
            )

        def sync_progress(progress: float, message: str = ""):
            try:
                loop = asyncio.get_event_loop()
                if loop.is_running():
                    asyncio.create_task(progress_callback(progress, message))
            except Exception:
                pass

        loop = asyncio.get_event_loop()
        result = await loop.run_in_executor(
            None,
            lambda: self.shap_curve_service.compute_curves(
                run_id=run_id,
                features=features,
                n_bins=n_bins,
                n_breakpoints=n_breakpoints,
                progress_callback=sync_progress,
            ),
        )

        if result is None:
            raise ValueError(f"Stability selection run not found: {run_id}")

        await self.event_publisher.publish_success(
            job_id=analysis_id,
            metrics=result,
            message="SHAP curve computation completed successfully",
        )

        return StreamMessage(
            message_id=f"{analysis_id}_result",
            message_type="shap_completed",
            payload={
                "analysis_id": analysis_id,
                "success": True,
                "result": result,
            },
            metadata=message.metadata,
        )

    async def on_permanent_failure(self, message: StreamMessage, error: Exception):
        """Handle permanent SHAP computation failure."""
        analysis_id = message.payload.get("analysis_id")
        if analysis_id:
            error_code = getattr(error, "error_code", "UNKNOWN_ERROR")
            await self.event_publisher.publish_failed(
                job_id=analysis_id,
                error=str(error),
                error_code=error_code,
                message=f"SHAP computation failed permanently after {message.retry_count} attempts",
            )
