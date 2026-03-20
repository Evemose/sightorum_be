"""Pipeline node for async stability selection analysis."""

import asyncio
import logging
from core.exceptions import MLTrainingError
from datasource.throttler import AsyncThrottlerWrapper
from dto.requests import StabilitySelectionRequest, SQLDatasourceConfig
from events.publisher import EventPublisher
from service.stability_selection_service import StabilitySelectionService
from typing import Optional

from .pipeline_node import PipelineNode, StreamMessage

logger = logging.getLogger(__name__)


class StabilitySelectionPipelineNode(PipelineNode):
    """
    Pipeline node for stability selection analysis.

    Consumes requests from a Redis stream, runs analysis in an executor,
    and publishes progress/result events using the same event channels as training.
    """

    def __init__(
            self,
            redis_url: str,
            input_stream: str,
            stability_selection_service: StabilitySelectionService,
            datasource_factory,
            event_publisher: EventPublisher,
            throttler: AsyncThrottlerWrapper,
            output_stream: Optional[str] = None,
            consumer_group: str = "analysis_workers",
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
            batch_size=10,
            retry_on_error=True,
            max_retries=3,
            worker_pool=worker_pool,
            backpressure=backpressure,
        )

        self.stability_selection_service = stability_selection_service
        self.datasource_factory = datasource_factory
        self.event_publisher = event_publisher
        self.throttler = throttler

    async def process(self, message: StreamMessage) -> Optional[StreamMessage]:
        """Process one async stability selection request."""
        analysis_id = message.payload.get("analysis_id")
        request_data = message.payload.get("request_data")

        if not analysis_id or not request_data:
            raise ValueError("Invalid message format: missing analysis_id or request_data")

        request = StabilitySelectionRequest(
            datasource=SQLDatasourceConfig(**request_data["datasource"]),
            target_column=request_data["target_column"],
            feature_columns=request_data.get("feature_columns"),
            control_features=request_data.get("control_features"),
            problem_type=request_data.get("problem_type"),
            bootstrap_runs=request_data.get("bootstrap_runs", 50),
            sample_fraction=request_data.get("sample_fraction", 0.8),
            correlation_threshold=request_data.get("correlation_threshold", 0.8),
            selection_top_k=request_data.get("selection_top_k"),
            random_state=request_data.get("random_state", 42),
        )

        logger.info(f"Stability selection {analysis_id} waiting for throttle slot")
        async with self.throttler:
            await self.event_publisher.publish_started(
                job_id=analysis_id,
                model_type="stability_selection",
                message=f"Stability selection started for target: {request.target_column}",
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

            datasource = self.datasource_factory()
            loop = asyncio.get_event_loop()
            result = await loop.run_in_executor(
                None,
                lambda: self.stability_selection_service.analyze(
                    request, datasource, progress_callback=sync_progress
                ),
            )

            await self.event_publisher.publish_success(
                job_id=analysis_id,
                metrics=result,
                message="Stability selection completed successfully",
            )

            return StreamMessage(
                message_id=f"{analysis_id}_result",
                message_type="stability_selection_completed",
                payload={
                    "analysis_id": analysis_id,
                    "success": True,
                    "run_id": result.get("run_id"),
                    "result": result,
                },
                metadata=message.metadata,
            )

    async def on_permanent_failure(self, message: StreamMessage, error: Exception):
        """Handle permanent async stability selection failure."""
        analysis_id = message.payload.get("analysis_id")
        if analysis_id:
            error_code = getattr(error, "error_code", "UNKNOWN_ERROR")
            await self.event_publisher.publish_failed(
                job_id=analysis_id,
                error=str(error),
                error_code=error_code,
                message=f"Stability selection failed permanently after {message.retry_count} attempts",
            )
