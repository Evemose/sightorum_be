"""Generic pipeline node for stream processing."""

import asyncio
import logging
import redis.asyncio as redis
from abc import ABC, abstractmethod
from core import ModelTrainingError
from dataclasses import dataclass, field
from datetime import datetime
from enum import Enum
from typing import Optional, Dict, Any, List, Callable

logger = logging.getLogger(__name__)


class MessageStatus(str, Enum):
    """Status of message processing."""
    PENDING = "pending"
    PROCESSING = "processing"
    COMPLETED = "completed"
    FAILED = "failed"
    RETRYING = "retrying"


@dataclass
class StreamMessage:
    """Generic stream message."""
    message_id: str
    message_type: str
    payload: Dict[str, Any]
    metadata: Dict[str, Any] = field(default_factory=dict)
    timestamp: datetime = field(default_factory=datetime.now)
    retry_count: int = 0

    @classmethod
    def from_redis(cls, message_id: str, fields: Dict[str, str]) -> "StreamMessage":
        """Create message from Redis stream data."""
        import json
        return cls(
            message_id=message_id,
            message_type=fields.get("message_type", "unknown"),
            payload=json.loads(fields.get("payload", "{}")),
            metadata=json.loads(fields.get("metadata", "{}")),
            timestamp=datetime.fromisoformat(fields.get("timestamp", datetime.now().isoformat())),
            retry_count=int(fields.get("retry_count", 0))
        )

    def to_redis(self) -> Dict[str, str]:
        """Convert message to Redis stream format."""
        import json
        return {
            "message_type": self.message_type,
            "payload": json.dumps(self.payload),
            "metadata": json.dumps(self.metadata),
            "timestamp": self.timestamp.isoformat(),
            "retry_count": str(self.retry_count)
        }


class PipelineNode(ABC):
    """
    Generic pipeline node for processing messages from Redis Streams.

    Each node:
    - Consumes messages from one or more input streams
    - Processes messages (abstract method)
    - Optionally forwards results to output stream(s)
    - Handles errors and retries
    - Tracks message status
    """

    def __init__(
            self,
            redis_url: str,
            input_streams: List[str],
            output_stream: Optional[str] = None,
            consumer_group: str = "default",
            consumer_name: Optional[str] = None,
            batch_size: int = 1,
            block_time_ms: int = 5000,
            retry_on_error: bool = True,
            max_retries: int = 3
    ):
        """
        Initialize pipeline node.

        Args:
            redis_url: Redis connection URL
            input_streams: List of input stream names to consume from
            output_stream: Optional output stream to forward results
            consumer_group: Consumer group name
            consumer_name: Unique consumer name (auto-generated if None)
            batch_size: Number of messages to read per batch
            block_time_ms: Time to block waiting for messages (ms)
            retry_on_error: Whether to retry failed messages
            max_retries: Maximum retry attempts
        """
        self.redis_url = redis_url
        self.input_streams = input_streams
        self.output_stream = output_stream
        self.consumer_group = consumer_group
        self.consumer_name = consumer_name or f"{self.__class__.__name__}-{id(self)}"
        self.batch_size = batch_size
        self.block_time_ms = block_time_ms
        self.retry_on_error = retry_on_error
        self.max_retries = max_retries

        self._client: Optional[redis.Redis] = None
        self._running = False
        self._message_handlers: Dict[str, Callable] = {}

    async def start(self):
        """Start consuming and processing messages."""
        self._running = True
        self._client = await redis.from_url(
            self.redis_url,
            encoding="utf-8",
            decode_responses=True
        )

        # Create consumer groups if they don't exist
        for stream in self.input_streams:
            try:
                await self._client.xgroup_create(
                    stream,
                    self.consumer_group,
                    id="0",
                    mkstream=True
                )
                logger.info(f"Created consumer group '{self.consumer_group}' for stream '{stream}'")
            except redis.ResponseError as e:
                if "BUSYGROUP" not in str(e):
                    logger.error(f"Error creating consumer group: {e}")

        logger.info(f"Pipeline node '{self.consumer_name}' started, consuming from: {self.input_streams}")

        try:
            while self._running:
                await self._process_batch()
        except asyncio.CancelledError:
            logger.info(f"Pipeline node '{self.consumer_name}' cancelled")
        except Exception as e:
            logger.exception(f"Pipeline node '{self.consumer_name}' error: {e}")
        finally:
            await self._shutdown()

    async def stop(self):
        """Stop consuming messages."""
        logger.info(f"Stopping pipeline node '{self.consumer_name}'")
        self._running = False

    async def _shutdown(self):
        """Clean shutdown."""
        if self._client:
            await self._client.close()
            self._client = None
        logger.info(f"Pipeline node '{self.consumer_name}' stopped")

    async def _process_batch(self):
        """Process a batch of messages from input streams."""
        # Build streams dict for xreadgroup
        streams = {stream: ">" for stream in self.input_streams}

        try:
            messages = await self._client.xreadgroup(
                groupname=self.consumer_group,
                consumername=self.consumer_name,
                streams=streams,
                count=self.batch_size,
                block=self.block_time_ms
            )

            if messages:
                # Create tasks for concurrent processing
                tasks = []
                for stream_name, message_list in messages:
                    for message_id, fields in message_list:
                        task = asyncio.create_task(
                            self._process_message(stream_name, message_id, fields)
                        )
                        tasks.append(task)

                # Wait for all tasks to complete
                if tasks:
                    await asyncio.gather(*tasks, return_exceptions=True)
            else:
                # No messages, small delay
                await asyncio.sleep(0.1)

        except redis.ResponseError as e:
            logger.error(f"Error reading from streams: {e}")
            await asyncio.sleep(1)

    async def _process_message(
            self,
            stream_name: str,
            message_id: str,
            fields: Dict[str, str]
    ):
        """Process a single message."""
        try:
            # Parse message
            message = StreamMessage.from_redis(message_id, fields)

            # Update status to processing
            await self._update_message_status(message, MessageStatus.PROCESSING)

            # Process message (abstract method)
            result = await self.process(message)

            # If result is returned and output stream exists, forward it
            if result is not None and self.output_stream:
                await self._forward_message(result)

            # Mark as completed
            await self._update_message_status(message, MessageStatus.COMPLETED)

            # Acknowledge message
            await self._acknowledge_message(stream_name, message_id)

        except Exception as e:
            logger.exception(f"Error processing message {message_id}: {e}")
            await self._handle_error(stream_name, message_id, fields, e)

    async def _handle_error(
            self,
            stream_name: str,
            message_id: str,
            fields: Dict[str, str],
            error: Exception
    ):
        """Handle message processing error."""
        message = StreamMessage.from_redis(message_id, fields)

        if not isinstance(error, ModelTrainingError) and self.retry_on_error and message.retry_count < self.max_retries:
            # Retry message
            message.retry_count += 1
            message.metadata["last_error"] = str(error)
            message.metadata["last_error_time"] = datetime.now().isoformat()

            logger.warning(
                f"Message {message_id} failed (attempt {message.retry_count}/{self.max_retries}), "
                f"re-enqueueing for retry"
            )

            await self._update_message_status(message, MessageStatus.RETRYING)

            # Re-add to stream
            await self._client.xadd(stream_name, message.to_redis())

        else:
            # Max retries reached or retry disabled
            logger.error(f"Message {message_id} permanently failed after {message.retry_count} attempts")
            await self._update_message_status(message, MessageStatus.FAILED)
            await self.on_permanent_failure(message, error)

        # Always acknowledge to remove from pending
        await self._acknowledge_message(stream_name, message_id)

    async def _acknowledge_message(self, stream_name: str, message_id: str):
        """Acknowledge message processing."""
        try:
            await self._client.xack(stream_name, self.consumer_group, message_id)
        except redis.ResponseError as e:
            logger.error(f"Error acknowledging message {message_id}: {e}")

    async def _forward_message(self, result: StreamMessage):
        """Forward result message to output stream."""
        if not self.output_stream:
            return

        try:
            message_id = await self._client.xadd(
                self.output_stream,
                result.to_redis()
            )
            logger.debug(f"Forwarded message to '{self.output_stream}': {message_id}")
        except Exception as e:
            logger.error(f"Error forwarding message to '{self.output_stream}': {e}")

    async def _update_message_status(self, message: StreamMessage, status: MessageStatus):
        """Update message status in metadata store."""
        status_key = f"message_status:{message.message_id}"
        try:
            await self._client.hset(
                status_key,
                mapping={
                    "status": status.value,
                    "updated_at": datetime.now().isoformat(),
                    "retry_count": str(message.retry_count)
                }
            )
            await self._client.expire(status_key, 86400)  # 24 hour TTL
        except Exception as e:
            logger.error(f"Error updating message status: {e}")

    async def get_message_status(self, message_id: str) -> Optional[Dict[str, Any]]:
        """Get message processing status."""
        status_key = f"message_status:{message_id}"
        try:
            status = await self._client.hgetall(status_key)
            return status if status else None
        except Exception:
            return None

    @abstractmethod
    async def process(self, message: StreamMessage) -> Optional[StreamMessage]:
        """
        Process a message.

        Args:
            message: Input message to process

        Returns:
            Optional output message to forward to next node.
            Return None if no forwarding needed.

        Raises:
            Exception: Any error during processing (will trigger retry logic)
        """
        pass

    async def on_permanent_failure(self, message: StreamMessage, error: Exception):
        """
        Called when message permanently fails after all retries.

        Override to implement custom failure handling (e.g., dead letter processing).

        Args:
            message: Failed message
            error: Final error that caused permanent failure
        """
        logger.error(
            f"Permanent failure for message {message.message_id}: {error}. "
            f"Override on_permanent_failure() for custom handling."
        )
