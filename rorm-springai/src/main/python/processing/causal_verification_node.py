"""Pipeline node for async causal verification pipeline execution."""

import asyncio
import logging
from datasource.throttler import AsyncThrottlerWrapper
from dto.causal_verification_request import CausalVerificationRequest
from events.publisher import EventPublisher
from service.causal_verification_service import CausalVerificationService
from service.pipeline_checkpoint import PipelineCheckpoint
from typing import Optional

from .pipeline_node import PipelineNode, StreamMessage

logger = logging.getLogger(__name__)


class CausalVerificationPipelineNode(PipelineNode):
    """
    Pipeline node for causal verification pipeline.

    Consumes PipelineSpec requests from a Redis stream, runs the full
    13-step causal pipeline in an executor, and publishes progress/result
    events using the standard event channels.

    Each step is checkpointed to Redis so that a retry (same analysis_id)
    skips already-completed steps.
    """

    def __init__(
            self,
            redis_url: str,
            input_stream: str,
            causal_verification_service: CausalVerificationService,
            datasource_factory,
            event_publisher: EventPublisher,
            throttler: AsyncThrottlerWrapper,
            output_stream: Optional[str] = None,
            consumer_group: str = "causal_verification_workers",
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
            max_retries=2,
            worker_pool=worker_pool,
            backpressure=backpressure,
        )

        self.causal_verification_service = causal_verification_service
        self.datasource_factory = datasource_factory
        self.event_publisher = event_publisher
        self.throttler = throttler

    async def process(self, message: StreamMessage) -> Optional[StreamMessage]:
        """Process one async causal verification request."""
        analysis_id = message.payload.get("analysis_id")
        request_data = message.payload.get("request_data")

        if not analysis_id or not request_data:
            raise ValueError("Invalid message format: missing analysis_id or request_data")

        spec = CausalVerificationRequest.from_dict(request_data)
        checkpoint = PipelineCheckpoint(run_id=analysis_id, redis_url=self.redis_url)

        cached_steps = checkpoint.completed_steps()
        if cached_steps:
            logger.info(
                f"Causal verification {analysis_id} resuming — "
                f"cached steps: {cached_steps}"
            )

        logger.info(
            f"Causal verification {analysis_id} for hypothesis {spec.hypothesis_id} "
            f"waiting for throttle slot"
        )
        async with self.throttler:
            await self.event_publisher.publish_started(
                job_id=analysis_id,
                model_type="causal_verification",
                message=f"Causal verification started for hypothesis: {spec.hypothesis_id}",
            )

            async def progress_callback(progress: float, msg: str = ""):
                await self.event_publisher.publish_progress(
                    job_id=analysis_id, progress=progress, message=msg
                )

            def sync_progress(progress: float, msg: str = ""):
                try:
                    loop = asyncio.get_event_loop()
                    if loop.is_running():
                        asyncio.create_task(progress_callback(progress, msg))
                except Exception:
                    pass

            datasource = self.datasource_factory()
            loop = asyncio.get_event_loop()
            result = await loop.run_in_executor(
                None,
                lambda: self.causal_verification_service.run_pipeline(
                    spec, datasource,
                    progress_callback=sync_progress,
                    checkpoint=checkpoint,
                ),
            )

            await self.event_publisher.publish_success(
                job_id=analysis_id,
                metrics=result,
                message="Causal verification pipeline completed successfully",
            )

            return StreamMessage(
                message_id=f"{analysis_id}_result",
                message_type="causal_verification_completed",
                payload={
                    "analysis_id": analysis_id,
                    "success": True,
                    "hypothesis_id": spec.hypothesis_id,
                    "result": result,
                },
                metadata=message.metadata,
            )

    async def on_permanent_failure(self, message: StreamMessage, error: Exception):
        """Handle permanent causal verification failure."""
        analysis_id = message.payload.get("analysis_id")
        if analysis_id:
            error_code = getattr(error, "error_code", "UNKNOWN_ERROR")
            await self.event_publisher.publish_failed(
                job_id=analysis_id,
                error=str(error),
                error_code=error_code,
                message=f"Causal verification failed permanently after {message.retry_count} attempts",
            )
