"""Event publisher for job progress and results."""

import datetime
import json
import logging
import os
import redis.asyncio as redis
import urllib.request
from enum import Enum
from typing import Any, Dict, Optional

logger = logging.getLogger(__name__)


def _fetch_ecs_task_arn() -> Optional[str]:
    uri = os.environ.get("ECS_CONTAINER_METADATA_URI_V4")
    if not uri:
        return None
    try:
        with urllib.request.urlopen(f"{uri}/task", timeout=2) as r:
            return json.load(r).get("TaskARN") or None
    except Exception as e:
        logger.warning(f"failed to fetch ECS task ARN: {e}")
        return None


class EventType(str, Enum):
    """Job event types."""
    JOB_STARTED = "job.started"
    JOB_PROGRESS = "job.progress"
    JOB_SUCCESS = "job.success"
    JOB_FAILED = "job.failed"
    VALIDATION_FAILED = "job.validation_failed"


class JobEvent:
    """Job event data structure."""

    def __init__(
            self,
            job_id: str,
            event_type: EventType,
            timestamp: Optional[datetime] = None,
            progress: Optional[float] = None,
            message: Optional[str] = None,
            metrics: Optional[Dict[str, Any]] = None,
            error: Optional[str] = None,
            error_code: Optional[str] = None,
            metadata: Optional[Dict[str, Any]] = None,
            worker_task_arn: Optional[str] = None,
    ):
        self.job_id = job_id
        self.event_type = event_type
        self.timestamp = timestamp or datetime.datetime.now(datetime.UTC)
        self.progress = progress
        self.message = message
        self.metrics = metrics
        self.error = error
        self.error_code = error_code
        self.metadata = metadata or {}
        self.worker_task_arn = worker_task_arn

    def to_dict(self) -> Dict[str, Any]:
        """Convert event to dictionary."""
        data: Dict[str, Any] = {
            "job_id": self.job_id,
            "event_type": self.event_type.value,
            "timestamp": self.timestamp.isoformat(),
        }

        if self.progress is not None:
            data["progress"] = self.progress
        if self.message:
            data["message"] = self.message
        if self.metrics:
            data["metrics"] = self.metrics
        if self.error:
            data["error"] = self.error
        if self.error_code:
            data["error_code"] = self.error_code
        if self.metadata:
            data["metadata"] = self.metadata
        if self.worker_task_arn:
            data["worker_task_arn"] = self.worker_task_arn

        return data

    def to_json(self) -> str:
        """Convert event to JSON string."""
        return json.dumps(self.to_dict(), default=str)


_TERMINAL_EVENTS = {
    EventType.JOB_SUCCESS,
    EventType.JOB_FAILED,
    EventType.VALIDATION_FAILED,
}


class EventPublisher:
    """Redis/Valkey event publisher for training events."""

    def __init__(
            self,
            redis_url: str = "redis://localhost:6379",
            channel_prefix: str = "ml_training",
            results_stream: Optional[str] = None
    ):
        self.redis_url = redis_url
        self.channel_prefix = channel_prefix
        self.results_stream = results_stream
        self._client: Optional[redis.Redis] = None
        self._worker_task_arn = _fetch_ecs_task_arn()

    async def connect(self):
        """Connect to Redis/Valkey."""
        if self._client is None:
            self._client = await redis.from_url(
                self.redis_url,
                encoding="utf-8",
                decode_responses=True
            )

    async def disconnect(self):
        """Disconnect from Redis/Valkey."""
        if self._client:
            await self._client.close()
            self._client = None

    async def add_to_stream(
            self,
            stream_name: str,
            message_data: Dict[str, Any]
    ):
        """
        Add message to Redis stream.

        Args:
            stream_name: Name of the stream
            message_data: Dictionary of field-value pairs for the message
        """
        if not self._client:
            await self.connect()
        await self._client.xadd(stream_name, message_data)

    async def publish(self, event: JobEvent):
        """
        Publish job event to Redis.

        Args:
            event: Job event to publish
        """
        if not self._client:
            await self.connect()

        # Publish to both general channel and job-specific channel
        channels = [
            f"{self.channel_prefix}.events",  # General events channel
            f"{self.channel_prefix}.{event.job_id}"  # Job-specific channel
        ]

        event_json = event.to_json()

        for channel in channels:
            await self._client.publish(channel, event_json)

        # Also store event in a sorted set for history (with timestamp as score)
        history_key = f"{self.channel_prefix}.history:{event.job_id}"
        timestamp_score = event.timestamp.timestamp()
        await self._client.zadd(history_key, {event_json: timestamp_score})

        # Expire history after 7 days
        await self._client.expire(history_key, 7 * 24 * 60 * 60)

        # XADD terminal events to results stream for Java fire-listen-wakeup
        if self.results_stream and event.event_type in _TERMINAL_EVENTS:
            await self._client.xadd(self.results_stream, {"payload": event_json})

    async def publish_started(
            self,
            job_id: str,
            model_type: str,
            message: Optional[str] = None
    ):
        event = JobEvent(
            job_id=job_id,
            event_type=EventType.JOB_STARTED,
            message=message or f"Started {model_type}",
            metadata={"model_type": model_type},
            worker_task_arn=self._worker_task_arn,
        )
        await self.publish(event)

    async def publish_progress(
            self,
            job_id: str,
            progress: float,
            message: Optional[str] = None
    ):
        """Publish job progress event."""
        event = JobEvent(
            job_id=job_id,
            event_type=EventType.JOB_PROGRESS,
            progress=progress,
            message=message or f"Progress: {progress:.1%}"
        )
        await self.publish(event)

    async def publish_success(
            self,
            job_id: str,
            metrics: Dict[str, Any],
            message: Optional[str] = None
    ):
        """Publish job success event."""
        event = JobEvent(
            job_id=job_id,
            event_type=EventType.JOB_SUCCESS,
            progress=1.0,
            message=message or "Completed successfully",
            metrics=metrics
        )
        await self.publish(event)

    async def publish_failed(
            self,
            job_id: str,
            error: str,
            error_code: Optional[str] = None,
            message: Optional[str] = None
    ):
        """Publish job failed event."""
        event = JobEvent(
            job_id=job_id,
            event_type=EventType.JOB_FAILED,
            error=error,
            error_code=error_code,
            message=message or "Job failed"
        )
        await self.publish(event)

    async def publish_validation_failed(
            self,
            job_id: str,
            error: str,
            error_code: str,
            message: Optional[str] = None
    ):
        """Publish validation failed event."""
        event = JobEvent(
            job_id=job_id,
            event_type=EventType.VALIDATION_FAILED,
            error=error,
            error_code=error_code,
            message=message or "Validation failed"
        )
        await self.publish(event)
