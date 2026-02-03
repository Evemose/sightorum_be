"""Training pipeline node for processing training requests."""

import asyncio
import logging
from core.exceptions import MLTrainingError
from datasource.throttler import AsyncThrottlerWrapper
from dto.requests import TrainingRequest, SQLDatasourceConfig
from events.publisher import EventPublisher
from service.training_service import TrainingService
from typing import Optional

from .pipeline_node import PipelineNode, StreamMessage

logger = logging.getLogger(__name__)


class TrainingPipelineNode(PipelineNode):
    """
    Pipeline node for training ML models.

    Consumes training requests from stream, executes training,
    and publishes events for progress tracking.
    """

    def __init__(
            self,
            redis_url: str,
            input_stream: str,
            training_service: TrainingService,
            datasource_factory,
            event_publisher: EventPublisher,
            throttler: AsyncThrottlerWrapper,
            output_stream: Optional[str] = None,
            consumer_group: str = "training_workers",
            consumer_name: Optional[str] = None
    ):
        """
        Initialize training pipeline node.

        Args:
            redis_url: Redis connection URL
            input_stream: Input stream name for training requests
            training_service: Training service instance
            datasource_factory: Factory function to create datasource
            event_publisher: Event publisher for progress updates
            throttler: Async throttler wrapper for concurrency control
            output_stream: Optional output stream for completed trainings
            consumer_group: Consumer group name
            consumer_name: Unique consumer name
        """
        super().__init__(
            redis_url=redis_url,
            input_streams=[input_stream],
            output_stream=output_stream,
            consumer_group=consumer_group,
            consumer_name=consumer_name,
            batch_size=10,  # Read multiple messages to enable concurrent processing
            retry_on_error=True,
            max_retries=3
        )

        self.training_service = training_service
        self.datasource_factory = datasource_factory
        self.event_publisher = event_publisher
        self.throttler = throttler

    async def process(self, message: StreamMessage) -> Optional[StreamMessage]:
        """
        Process training request message.

        Args:
            message: Training request message

        Returns:
            Optional result message with training metadata

        Raises:
            MLTrainingError: If training fails
        """
        training_id = message.payload.get("training_id")
        request_data = message.payload.get("request_data")

        if not training_id or not request_data:
            raise ValueError("Invalid message format: missing training_id or request_data")

        # Reconstruct training request
        training_request = TrainingRequest(
            model_type=request_data["model_type"],
            model_name=request_data["model_name"],
            datasource=SQLDatasourceConfig(**request_data["datasource"]),
            target_column=request_data.get("target_column"),
            feature_columns=request_data.get("feature_columns"),
            model_params=request_data.get("model_params", {})
        )

        # Wait for throttle slot
        logger.info(f"Training {training_id} waiting for throttle slot")
        async with self.throttler:
            # Publish training started (after passing throttle)
            await self.event_publisher.publish_started(
                training_id=training_id,
                model_type=training_request.model_type,
                message=f"Training started for model: {training_request.model_name}"
            )

            # Create progress callback
            async def progress_callback(progress: float, message_text: str = ""):
                await self.event_publisher.publish_progress(
                    training_id=training_id,
                    progress=progress,
                    message=message_text
                )

            # Create datasource
            datasource = self.datasource_factory()

            # Run training in executor
            loop = asyncio.get_event_loop()
            response = await loop.run_in_executor(
                None,
                lambda: self.training_service.train(
                    request=training_request,
                    datasource=datasource,
                    progress_callback=self._create_sync_callback(progress_callback)
                )
            )

            # Check if training was successful
            if response.success:
                # Publish success event
                await self.event_publisher.publish_success(
                    training_id=training_id,
                    metrics=response.metrics or {},
                    message=f"Training completed successfully for {training_request.model_name}"
                )

                # Return result message for potential forwarding
                return StreamMessage(
                    message_id=f"{training_id}_result",
                    message_type="training_completed",
                    payload={
                        "training_id": training_id,
                        "model_name": training_request.model_name,
                        "model_type": training_request.model_type,
                        "success": True,
                        "metrics": response.metrics,
                        "model_metadata": response.model_metadata.to_dict() if response.model_metadata else None
                    },
                    metadata=message.metadata
                )
            else:
                # Training returned error response - raise to trigger retry logic
                raise MLTrainingError(
                    message=response.message,
                    error_code=response.error_code or "TRAINING_ERROR",
                    details=response.error_details
                )

    async def on_permanent_failure(self, message: StreamMessage, error: Exception):
        """Handle permanent training failure."""
        training_id = message.payload.get("training_id")
        if training_id:
            error_code = getattr(error, "error_code", "UNKNOWN_ERROR")
            await self.event_publisher.publish_failed(
                training_id=training_id,
                error=str(error),
                error_code=error_code,
                message=f"Training failed permanently after {message.retry_count} attempts"
            )

    def _create_sync_callback(self, async_callback):
        """Create synchronous callback wrapper for async callback."""

        def sync_callback(progress: float, message: str = ""):
            try:
                loop = asyncio.get_event_loop()
                if loop.is_running():
                    asyncio.create_task(async_callback(progress, message))
                else:
                    asyncio.run(async_callback(progress, message))
            except Exception:
                # Silently ignore callback errors
                pass

        return sync_callback
