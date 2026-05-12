"""
Checkpoint + run-metadata store for long-running pipeline runs.

Two distinct concerns live here:

* Per-step output checkpoints (``causal_cp:{run_id}:{step}``) — let a
  pipeline resume after a crash and let ``reexecuteCausalPipeline``
  skip steps unaffected by a spec patch. 7-day TTL.
* Run-level metadata (``causal_run:{run_id}:meta``) — records what
  spec each run was launched with, lineage of reexecutions (parent +
  patch), status, and the final result. Lets the reexecution engine
  recover the base spec from a run id alone, without the caller having
  to thread it through. 30-day TTL.

DataFrames and other large in-memory objects are NOT checkpointed —
the caller must re-derive them (e.g., re-run the data-loading query).
"""

import json
import logging
import redis
from typing import Any, Optional

logger = logging.getLogger(__name__)

_KEY_PREFIX = "causal_cp"
_DEFAULT_TTL = 7 * 24 * 3600  # 7 days
_META_TTL = 30 * 24 * 3600  # 30 days for run metadata


class PipelineCheckpoint:
    """
    Redis-backed synchronous checkpoint + run-metadata store.

    Key layout::

        causal_cp:{run_id}:{step}   → JSON blob with the step's variables
        causal_cp:{run_id}:_order   → JSON list of step names in completion order
        causal_run:{run_id}:meta    → JSON run metadata (spec OR lineage)
        causal_runs:index           → sorted set of run_ids by completion time

    Step keys get a 7-day TTL; meta keys get a 30-day TTL.
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

    # -- run-level metadata -----------------------------------------------

    def save_run_meta(self, metadata: dict[str, Any]) -> None:
        """Persist run-level metadata.

        Two valid shapes:
        - Root run: ``{status, spec, started_at, ...}`` — full spec recorded.
        - Reexecution: ``{status, parent_run_id, patch, started_at, ...}``
          — only the patch is stored; the merged spec is recovered by
          walking the lineage chain to the root.
        """
        if not self._available:
            return
        key = f"causal_run:{self._run_id}:meta"
        try:
            self._client.set(
                key, json.dumps(metadata, default=_json_fallback),
                ex=_META_TTL,
            )
            self._client.zadd(
                "causal_runs:index",
                {self._run_id: metadata.get("_score", 0)},
                nx=False,
            )
        except Exception as e:
            logger.warning(f"Run metadata save failed: {e}")

    def load_run_meta(self) -> Optional[dict[str, Any]]:
        """Load run-level metadata, or ``None`` if the run is unknown."""
        if not self._available:
            return None
        key = f"causal_run:{self._run_id}:meta"
        try:
            raw = self._client.get(key)
            return json.loads(raw) if raw else None
        except Exception:
            return None

    @classmethod
    def list_runs(cls, redis_url: str, limit: int = 50) -> list[dict[str, Any]]:
        """List recent runs with their metadata (most recent first)."""
        try:
            client = redis.from_url(
                redis_url, encoding="utf-8", decode_responses=True,
            )
            run_ids = client.zrevrange("causal_runs:index", 0, limit - 1)
            runs = []
            for rid in run_ids:
                raw = client.get(f"causal_run:{rid}:meta")
                if raw:
                    meta = json.loads(raw)
                    meta["run_id"] = rid
                    meta.pop("result", None)
                    meta.pop("spec", None)
                    meta.pop("patch", None)
                    runs.append(meta)
            return runs
        except Exception:
            return []

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
