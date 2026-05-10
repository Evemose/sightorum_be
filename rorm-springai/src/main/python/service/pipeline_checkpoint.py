"""
Checkpoint store for long-running pipeline runs.

Persists each step's output variables to Redis so that a pipeline
can resume from the last completed step after a crash or retry,
and so that reexecuteCausalPipeline can skip steps unaffected by
a spec patch.

DataFrames and other large in-memory objects are NOT checkpointed —
the caller must re-derive them (e.g., re-run the data-loading query).

Run-level metadata (spec, status, final result) is NOT persisted
here — the swarm carries it in memory via its ToolCallRegistry.
Only per-step pipeline checkpoints (causal_cp:{run_id}:{step}) live
in Valkey, and only to enable step reuse during reexecution.
"""

import json
import logging
import redis
from typing import Any, Optional

logger = logging.getLogger(__name__)

_KEY_PREFIX = "causal_cp"
_DEFAULT_TTL = 7 * 24 * 3600  # 7 days


class PipelineCheckpoint:
    """
    Redis-backed synchronous checkpoint store.

    Key layout::

        causal_cp:{run_id}:{step}  → JSON blob with the step's variables
        causal_cp:{run_id}:_order  → JSON list of step names in completion order

    Every key gets a TTL so abandoned runs clean themselves up.
    """

    def __init__(self, run_id: str, redis_url: str, ttl_seconds: int = _DEFAULT_TTL):
        self._run_id = run_id
        self._ttl = ttl_seconds
        try:
            self._client = redis.from_url(
                redis_url, encoding="utf-8", decode_responses=True,
            )
            self._client.ping()
            self._available = True
        except Exception as e:
            logger.warning(f"Checkpoint store unavailable (pipeline will run without checkpoints): {e}")
            self._client = None
            self._available = False

    # -- public API -------------------------------------------------------

    def save(self, step: str, data: dict[str, Any]) -> None:
        """Persist *data* for *step*.  Silently no-ops if Redis is down."""
        if not self._available:
            return
        key = self._key(step)
        try:
            self._client.set(key, json.dumps(data, default=_json_fallback), ex=self._ttl)
            self._append_order(step)
            logger.info(f"Checkpoint saved: {step}  (run={self._run_id})")
        except Exception as e:
            logger.warning(f"Checkpoint save failed for step '{step}': {e}")

    def load(self, step: str) -> Optional[dict[str, Any]]:
        """Return the saved dict for *step*, or ``None`` if absent."""
        if not self._available:
            return None
        try:
            raw = self._client.get(self._key(step))
            if raw is None:
                return None
            logger.info(f"Checkpoint hit: {step}  (run={self._run_id})")
            return json.loads(raw)
        except Exception as e:
            logger.warning(f"Checkpoint load failed for step '{step}': {e}")
            return None

    def completed_steps(self) -> list[str]:
        """Return the names of steps that have been checkpointed, in order."""
        if not self._available:
            return []
        try:
            raw = self._client.get(self._key("_order"))
            return json.loads(raw) if raw else []
        except Exception:
            return []

    def clear(self) -> None:
        """Remove all checkpoints for this run."""
        if not self._available:
            return
        try:
            order = self.completed_steps()
            keys = [self._key(s) for s in order] + [self._key("_order")]
            if keys:
                self._client.delete(*keys)
            logger.info(f"Checkpoints cleared  (run={self._run_id})")
        except Exception as e:
            logger.warning(f"Checkpoint clear failed: {e}")

    # -- internals --------------------------------------------------------

    def _key(self, step: str) -> str:
        return f"{_KEY_PREFIX}:{self._run_id}:{step}"

    def _append_order(self, step: str) -> None:
        order_key = self._key("_order")
        try:
            raw = self._client.get(order_key)
            order = json.loads(raw) if raw else []
            if step not in order:
                order.append(step)
            self._client.set(order_key, json.dumps(order), ex=self._ttl)
        except Exception:
            pass


def _json_fallback(obj):
    """Fallback serializer for types that json.dumps cannot handle natively."""
    if hasattr(obj, "tolist"):  # numpy scalars / arrays
        return obj.tolist()
    if hasattr(obj, "item"):  # numpy scalar
        return obj.item()
    raise TypeError(f"Object of type {type(obj).__name__} is not JSON serializable")
