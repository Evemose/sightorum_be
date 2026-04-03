"""Application-wide worker pool with memory budget tracking."""

import logging
import os
import psutil
import threading
from concurrent.futures import Future, ThreadPoolExecutor
from dataclasses import dataclass
from typing import Callable, TypeVar

logger = logging.getLogger(__name__)

T = TypeVar("T")


@dataclass(frozen=True)
class PoolMetrics:
    total_workers: int
    active_workers: int
    free_workers: int
    memory_budget_bytes: int
    reserved_memory_bytes: int
    free_memory_bytes: int

    @property
    def worker_utilization(self) -> float:
        return self.active_workers / self.total_workers if self.total_workers > 0 else 0.0

    @property
    def memory_utilization(self) -> float:
        return self.reserved_memory_bytes / self.memory_budget_bytes if self.memory_budget_bytes > 0 else 0.0


class WorkerPool:
    """
    Singleton thread pool with memory budget tracking.

    All CPU-bound parallel work goes through this pool instead of ad-hoc
    ThreadPoolExecutor creation. The pool enforces a memory budget: submit()
    blocks the caller until the requested memory fits within the remaining budget.

    Pipeline nodes use metrics for backpressure decisions.
    """

    _instance: 'WorkerPool | None' = None
    _init_lock = threading.Lock()

    SAFETY_FACTOR = 0.75  # reserve 25% headroom for internal copies, GC lag

    def __init__(self, max_workers: int, memory_budget_bytes: int):
        self._max_workers = max_workers
        self._memory_budget_bytes = int(memory_budget_bytes * self.SAFETY_FACTOR)
        self._executor = ThreadPoolExecutor(
            max_workers=max_workers,
            thread_name_prefix="pool-",
        )
        self._active_workers = 0
        self._reserved_memory_bytes = 0
        self._cond = threading.Condition()
        logger.info(
            f"WorkerPool: {max_workers} workers, "
            f"{self._memory_budget_bytes / (1024 ** 3):.1f} GB effective memory budget "
            f"({memory_budget_bytes / (1024 ** 3):.1f} GB raw, {self.SAFETY_FACTOR:.0%} safety factor)"
        )

    @classmethod
    def configure(cls, max_workers: int = 0, memory_budget_gb: float = 0.0) -> 'WorkerPool':
        with cls._init_lock:
            if cls._instance is not None:
                cls._instance.shutdown(wait=False)
            if max_workers <= 0:
                max_workers = max(2, (os.cpu_count() or 4))
            if memory_budget_gb <= 0.0:
                try:
                    memory_budget_gb = (psutil.virtual_memory().available / (1024 ** 3)) * 0.6
                except Exception:
                    memory_budget_gb = 4.0
            memory_budget_bytes = int(memory_budget_gb * (1024 ** 3))
            cls._instance = cls(max_workers, memory_budget_bytes)
            return cls._instance

    @classmethod
    def instance(cls) -> 'WorkerPool':
        if cls._instance is None:
            raise RuntimeError("WorkerPool not configured")
        return cls._instance

    def submit(self, memory_estimate_bytes: int, fn: Callable[[], T]) -> 'Future[T]':
        """
        Submit work with a memory reservation.

        Blocks the caller until the memory budget can accommodate the request.
        A single task is always admitted when no other reservations are held,
        even if it exceeds the total budget.
        """
        with self._cond:
            while (self._reserved_memory_bytes + memory_estimate_bytes > self._memory_budget_bytes
                   and self._reserved_memory_bytes > 0):
                self._cond.wait()
            self._reserved_memory_bytes += memory_estimate_bytes

        def wrapper():
            with self._cond:
                self._active_workers += 1
            try:
                return fn()
            finally:
                with self._cond:
                    self._reserved_memory_bytes -= memory_estimate_bytes
                    self._active_workers -= 1
                    self._cond.notify_all()

        return self._executor.submit(wrapper)

    @property
    def metrics(self) -> PoolMetrics:
        with self._cond:
            reserved = self._reserved_memory_bytes
            active = self._active_workers
        return PoolMetrics(
            total_workers=self._max_workers,
            active_workers=active,
            free_workers=max(0, self._max_workers - active),
            memory_budget_bytes=self._memory_budget_bytes,
            reserved_memory_bytes=reserved,
            free_memory_bytes=max(0, self._memory_budget_bytes - reserved),
        )

    def shutdown(self, wait: bool = True):
        self._executor.shutdown(wait=wait)
        with self._cond:
            self._cond.notify_all()
        logger.info("WorkerPool shut down")
