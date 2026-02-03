"""
Query throttling for resource management.

Provides rate limiting and concurrency control to prevent
overwhelming the database with expensive queries.
"""

import asyncio
import threading
import time
from contextlib import contextmanager
from core.exceptions import ThrottlingError
from dataclasses import dataclass
from typing import Generator


@dataclass
class ThrottleStatus:
    """Current status of the throttler."""

    current_queries: int
    max_queries: int
    is_at_capacity: bool
    wait_time_seconds: int


class QueryThrottler:
    """
    Thread-safe query throttler with semaphore-based concurrency control.

    Limits the number of concurrent queries to prevent resource exhaustion.
    """

    def __init__(self, max_concurrent: int = 5, wait_timeout: int = 10):
        """
        Initialize the throttler.

        Args:
            max_concurrent: Maximum number of concurrent queries
            wait_timeout: Seconds to wait for a slot before raising ThrottlingError
        """
        self._max_concurrent = max_concurrent
        self._wait_timeout = wait_timeout
        self._semaphore = threading.Semaphore(max_concurrent)
        self._current_count = 0
        self._lock = threading.Lock()

    def get_status(self) -> ThrottleStatus:
        """Get current throttler status."""
        with self._lock:
            return ThrottleStatus(
                current_queries=self._current_count,
                max_queries=self._max_concurrent,
                is_at_capacity=self._current_count >= self._max_concurrent,
                wait_time_seconds=self._wait_timeout,
            )

    @contextmanager
    def acquire(self) -> Generator[None, None, None]:
        """
        Context manager to acquire a query slot.

        Raises:
            ThrottlingError: If unable to acquire a slot within timeout
        """
        acquired = self._semaphore.acquire(timeout=self._wait_timeout)

        if not acquired:
            status = self.get_status()
            raise ThrottlingError.max_concurrent_reached(
                current=status.current_queries,
                maximum=status.max_queries,
                wait_seconds=status.wait_time_seconds,
            )

        with self._lock:
            self._current_count += 1

        try:
            yield
        finally:
            with self._lock:
                self._current_count -= 1
            self._semaphore.release()

    def try_acquire(self) -> bool:
        """
        Attempt to acquire a query slot without waiting.

        Returns:
            True if slot acquired, False otherwise
        """
        acquired = self._semaphore.acquire(blocking=False)
        if acquired:
            with self._lock:
                self._current_count += 1
        return acquired

    def release(self) -> None:
        """Release a query slot."""
        with self._lock:
            self._current_count -= 1
        self._semaphore.release()


class AdaptiveThrottler(QueryThrottler):
    """
    Adaptive throttler that adjusts limits based on query performance.

    Extends the basic throttler with:
    - Query duration tracking
    - Automatic limit adjustment based on performance
    - Slow query detection
    """

    def __init__(
            self,
            max_concurrent: int = 5,
            wait_timeout: int = 10,
            slow_query_threshold_ms: int = 5000,
            adjustment_window: int = 100,
    ):
        super().__init__(max_concurrent, wait_timeout)
        self._slow_query_threshold_ms = slow_query_threshold_ms
        self._adjustment_window = adjustment_window
        self._query_times: list[float] = []
        self._slow_query_count = 0

    @contextmanager
    def acquire(self) -> Generator[None, None, None]:
        """Acquire slot and track query duration."""
        with super().acquire():
            start_time = time.time()
            try:
                yield
            finally:
                duration_ms = (time.time() - start_time) * 1000
                self._record_query_time(duration_ms)

    def _record_query_time(self, duration_ms: float) -> None:
        """Record query duration and update metrics."""
        with self._lock:
            self._query_times.append(duration_ms)
            if duration_ms > self._slow_query_threshold_ms:
                self._slow_query_count += 1

            # Keep only the adjustment window
            if len(self._query_times) > self._adjustment_window:
                oldest_time = self._query_times.pop(0)
                if oldest_time > self._slow_query_threshold_ms:
                    self._slow_query_count = max(0, self._slow_query_count - 1)

    def get_metrics(self) -> dict:
        """Get throttler metrics."""
        with self._lock:
            if not self._query_times:
                return {
                    "avg_query_time_ms": 0,
                    "slow_query_ratio": 0,
                    "total_queries_tracked": 0,
                }
            return {
                "avg_query_time_ms": sum(self._query_times) / len(self._query_times),
                "slow_query_ratio": self._slow_query_count / len(self._query_times),
                "total_queries_tracked": len(self._query_times),
            }


class AsyncThrottlerWrapper:
    """
    Async wrapper for synchronous throttler.

    Provides async context manager interface for use in async code
    while delegating to the underlying synchronous throttler.
    """

    def __init__(self, throttler: QueryThrottler):
        """
        Initialize async wrapper.

        Args:
            throttler: Synchronous throttler to wrap
        """
        self.throttler = throttler

    async def __aenter__(self):
        """Async context manager entry."""
        # Acquire in thread pool to avoid blocking event loop
        loop = asyncio.get_event_loop()
        acquired = await loop.run_in_executor(
            None,
            lambda: self.throttler._semaphore.acquire(timeout=self.throttler._wait_timeout)
        )

        if not acquired:
            status = self.throttler.get_status()
            raise ThrottlingError.max_concurrent_reached(
                current=status.current_queries,
                maximum=status.max_queries,
                wait_seconds=status.wait_time_seconds,
            )

        with self.throttler._lock:
            self.throttler._current_count += 1

        return self

    async def __aexit__(self, exc_type, exc_val, exc_tb):
        """Async context manager exit."""
        with self.throttler._lock:
            self.throttler._current_count -= 1
        self.throttler._semaphore.release()
        return False

    def get_status(self) -> ThrottleStatus:
        """Get current throttler status."""
        return self.throttler.get_status()
