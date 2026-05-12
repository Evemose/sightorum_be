"""
Re-execution engine for the causal verification pipeline.

Holds a statically-defined DAG that maps every PipelineSpec field and
every step's output variables to the inputs of downstream steps.

Given a *partial* spec patch it:
1. deep-merges the patch into the base run's frozen spec,
2. walks the DAG to find which steps are invalidated,
3. re-executes only those steps (others are loaded from the base run),
4. produces per-variable diffs between old and new values,
5. stores the re-execution as a new frozen run.

Every completed run (initial or re-execution) is frozen and can itself
serve as a base for further re-executions.
"""

import copy
import logging
import uuid
from collections import deque
from dataclasses import dataclass
from datetime import datetime
from typing import Any, Optional

from dto.causal_verification_request import CausalVerificationRequest
from service.pipeline_checkpoint import PipelineCheckpoint

logger = logging.getLogger(__name__)


# ======================================================================
# Static dependency DAG
# ======================================================================

@dataclass(frozen=True)
class StepDef:
    """
    Declares one pipeline step's dependencies.

    *spec_inputs*  – top-level PipelineSpec field names that, when
                     changed, force this step to re-execute.
    *step_inputs*  – names of other steps whose *output variables*
                     feed into this step.  If any of those steps are
                     invalidated this step is transitively invalidated.
    """
    spec_inputs: frozenset[str]
    step_inputs: frozenset[str]


PIPELINE_DAG: dict[str, StepDef] = {
    # Step 0 — always re-runs (DataFrames not serialisable), but only
    # counts as *invalidated* when its own spec fields change.
    "load_data": StepDef(
        spec_inputs=frozenset({"datasource", "strip_columns"}),
        step_inputs=frozenset(),
    ),
    # Step 1
    "dsep": StepDef(
        spec_inputs=frozenset({"dag_edges", "dsep_threshold"}),
        step_inputs=frozenset({"load_data"}),
    ),
    # Step 2 — pure spec, no data dependency
    "identification": StepDef(
        spec_inputs=frozenset({"adjustment_set", "mediators_excluded"}),
        step_inputs=frozenset(),
    ),
    # Step 3
    "estimation": StepDef(
        spec_inputs=frozenset({"estimation_variants", "outcome"}),
        step_inputs=frozenset({"load_data", "dsep"}),
    ),
    # Step 4
    "gates": StepDef(
        spec_inputs=frozenset({"gates", "outcome", "treatment"}),
        step_inputs=frozenset({"load_data", "identification", "estimation"}),
    ),
    # Step 5
    "mediation": StepDef(
        spec_inputs=frozenset({"mediation", "outcome", "treatment"}),
        step_inputs=frozenset({"load_data", "identification", "estimation"}),
    ),
    # Step 6
    "grf": StepDef(
        spec_inputs=frozenset({"grf_configs", "outcome", "treatment"}),
        step_inputs=frozenset({"load_data", "identification"}),
    ),
    # Step 7
    "refutations": StepDef(
        spec_inputs=frozenset({"refutations", "treatment", "outcome", "gates"}),
        step_inputs=frozenset({"load_data", "dsep", "estimation"}),
    ),
    # Step 8
    "unmeasured_confounding": StepDef(
        spec_inputs=frozenset({"unmeasured_confounding", "treatment", "outcome"}),
        step_inputs=frozenset({"load_data", "estimation"}),
    ),
    # Step 9
    "sensitivity": StepDef(
        spec_inputs=frozenset({"sensitivity", "treatment", "outcome"}),
        step_inputs=frozenset({"load_data", "dsep", "estimation"}),
    ),
    # Step 10
    "structural_breaks": StepDef(
        spec_inputs=frozenset({"structural_breaks", "outcome", "treatment"}),
        step_inputs=frozenset({"load_data", "estimation"}),
    ),
    # Step 11
    "residual_diagnostics": StepDef(
        spec_inputs=frozenset({"residual_checks", "treatment", "outcome"}),
        step_inputs=frozenset({"load_data", "dsep", "identification", "estimation"}),
    ),
    # Step 12
    "range_checks": StepDef(
        spec_inputs=frozenset({"range_checks", "treatment"}),
        step_inputs=frozenset({"load_data", "identification", "estimation"}),
    ),
    # Step 13
    "externalization": StepDef(
        spec_inputs=frozenset({"externalization"}),
        step_inputs=frozenset({"load_data", "grf"}),
    ),
}

STEP_ORDER: list[str] = [
    "load_data",
    "dsep",
    "identification",
    "estimation",
    "gates",
    "mediation",
    "grf",
    "refutations",
    "unmeasured_confounding",
    "sensitivity",
    "structural_breaks",
    "residual_diagnostics",
    "range_checks",
    "externalization",
]


# ======================================================================
# Engine
# ======================================================================


class ReexecutionEngine:

    def __init__(self, causal_service, redis_url: str):
        self._service = causal_service
        self._redis_url = redis_url

    def reexecute(
            self,
            base_run_id: str,
            spec_patch: dict[str, Any],
            datasource,
            base_spec: Optional[dict[str, Any]] = None,
            progress_callback=None,
            new_run_id: str | None = None,
    ) -> dict[str, Any]:
        """
        Re-execute the pipeline with a partial spec change.

        The base spec is resolved in this order:

        1. If ``base_spec`` is supplied by the caller, use it directly
           (backward-compat path; the swarm's ToolCallRegistry may pass
           it from in-memory state).
        2. Otherwise, look up the base run's metadata in Valkey:
           - Root runs store their full spec → use it.
           - Reexec runs store ``parent_run_id`` + ``patch`` → walk the
             chain to a root, replay patches forward.

        Parameters
        ----------
        base_run_id : str
            The base run whose step checkpoints will be reused.
        spec_patch : dict
            Partial PipelineSpec — only the fields that changed.
        datasource
            SQL datasource for data loading.
        base_spec : dict, optional
            Caller-supplied merged spec. Overrides chain-walk recovery
            when provided. Kept for backward compat with callers that
            still thread it through.
        progress_callback
            Optional ``(pct, msg)`` callback.
        new_run_id : str, optional
            ID to use for the new run. If omitted, a UUID is minted.

        Returns
        -------
        dict with ``run_id``, ``parent_run_id``, ``changed_spec_fields``,
        ``reexecuted_steps``, ``skipped_steps``, ``diffs``, and the
        full pipeline ``result``.
        """
        base_cp = PipelineCheckpoint(base_run_id, self._redis_url)

        # 1. Resolve base spec — caller-supplied takes precedence,
        # otherwise walk the lineage chain.
        if base_spec is None:
            base_spec = _resolve_spec_via_chain(base_run_id, self._redis_url)

        # 2. Merge patch → new full spec
        merged = deep_merge(base_spec, spec_patch)

        # 3. Determine which top-level fields actually changed
        changed_fields = find_changed_fields(base_spec, merged)
        if not changed_fields:
            # Nothing changed — return a no-op result. We do NOT have the
            # prior run's final result here (the swarm holds it); caller
            # already has it indexed by base_run_id.
            return {
                "run_id": None,
                "parent_run_id": base_run_id,
                "changed_spec_fields": [],
                "reexecuted_steps": [],
                "skipped_steps": list(STEP_ORDER),
                "diffs": {},
                "result": None,
            }

        # 3. Walk DAG to find invalidated steps
        invalidated = find_invalidated_steps(changed_fields)
        logger.info(
            f"Re-execution: changed={sorted(changed_fields)}, "
            f"invalidated={sorted(invalidated)}"
        )

        # 4. New run. Caller supplies the id (Restate's analysis_id) so that
        # job_id == run_id end-to-end, matching the fresh-run convention in
        # _process_fresh_run. If not supplied (older clients), fall back to a
        # minted uuid to preserve the prior behaviour.
        if new_run_id is None:
            new_run_id = str(uuid.uuid4())
        new_cp = PipelineCheckpoint(new_run_id, self._redis_url)

        # 5. Copy checkpoints for non-invalidated steps
        for step in STEP_ORDER:
            if step in invalidated or step == "load_data":
                continue
            cached = base_cp.load(step)
            if cached is not None:
                new_cp.save(step, cached)

        # 6. Execute pipeline (cached steps are skipped automatically).
        # run_pipeline writes a full-spec meta record by default — fine
        # for fresh runs, but we want the lean lineage form for reexecs.
        spec = CausalVerificationRequest.from_dict(merged)
        result = self._service.run_pipeline(
            spec, datasource,
            progress_callback=progress_callback,
            checkpoint=new_cp,
        )

        # 7. Overwrite meta with lean lineage form: keep parent + patch,
        # drop the merged spec. The chain walk recovers it on demand.
        existing_meta = new_cp.load_run_meta() or {}
        new_cp.save_run_meta({
            "status": "completed",
            "parent_run_id": base_run_id,
            "patch": spec_patch,
            "started_at": existing_meta.get("started_at"),
            "completed_at": existing_meta.get("completed_at"),
            "_score": existing_meta.get("_score", 0),
            "result": result,
        })

        # 8. Compute diffs between base and new for invalidated steps
        diffs = _compute_diffs(base_cp, new_cp, invalidated)

        skipped = [s for s in STEP_ORDER if s not in invalidated]
        return {
            "run_id": new_run_id,
            "parent_run_id": base_run_id,
            "changed_spec_fields": sorted(changed_fields),
            "reexecuted_steps": sorted(invalidated),
            "skipped_steps": skipped,
            "diffs": diffs,
            "result": result,
        }


# ======================================================================
# DAG invalidation
# ======================================================================

_MAX_CHAIN_DEPTH = 100


class SpecNotFoundError(ValueError):
    """Raised when the merged spec for a run cannot be recovered.

    Carries ``error_code="SPEC_NOT_FOUND"`` so the async failure pathway
    (``on_permanent_failure`` → ``publish_failed(error_code=...)``)
    surfaces a structured code to upstream callers instead of opaque
    UNKNOWN_ERROR.

    Causes covered: missing run metadata (never existed / TTL expired),
    malformed lineage record, cycles in the parent chain, depth cap
    exceeded. All point at the same end state for the caller — the spec
    is irrecoverable from server-side state and must be supplied
    explicitly via ``base_spec``.
    """

    error_code = "SPEC_NOT_FOUND"

    def __init__(self, message: str, run_id: str, reason: str):
        super().__init__(message)
        self.run_id = run_id
        self.reason = reason


def _resolve_spec_via_chain(
        run_id: str,
        redis_url: str,
        _depth: int = 0,
        _seen: set[str] | None = None,
) -> dict[str, Any]:
    """Recover the merged PipelineSpec for *run_id* by walking lineage.

    Root runs store ``spec`` directly. Reexec runs store ``parent_run_id``
    + ``patch``; recurse to the root and replay forward.

    Raises ``SpecNotFoundError`` for any failure to recover (missing
    meta, malformed record, cycle, depth cap) — callers can catch this
    specifically and either supply ``base_spec`` directly or surface a
    coherent error to the user.
    """
    if _seen is None:
        _seen = set()
    if run_id in _seen:
        raise SpecNotFoundError(
            f"Cycle detected in reexecution lineage at run '{run_id}' "
            f"(visited: {sorted(_seen)})",
            run_id=run_id, reason="lineage_cycle",
        )
    if _depth > _MAX_CHAIN_DEPTH:
        raise SpecNotFoundError(
            f"Reexecution chain for run '{run_id}' exceeds max depth "
            f"{_MAX_CHAIN_DEPTH} — likely a runaway loop",
            run_id=run_id, reason="depth_exceeded",
        )
    _seen.add(run_id)

    cp = PipelineCheckpoint(run_id, redis_url)
    meta = cp.load_run_meta()
    if meta is None:
        raise SpecNotFoundError(
            f"Cannot resolve spec for run '{run_id}': no run metadata "
            f"found (run never existed, never completed, or metadata TTL "
            f"expired — default 30 days)",
            run_id=run_id, reason="meta_missing",
        )

    if "spec" in meta and isinstance(meta["spec"], dict):
        return copy.deepcopy(meta["spec"])

    parent_run_id = meta.get("parent_run_id")
    patch = meta.get("patch")
    if not parent_run_id or patch is None:
        raise SpecNotFoundError(
            f"Run '{run_id}' metadata is malformed: has neither a full "
            f"spec nor lineage (parent_run_id + patch). Keys: "
            f"{sorted(meta.keys())}",
            run_id=run_id, reason="meta_malformed",
        )

    parent_spec = _resolve_spec_via_chain(
        parent_run_id, redis_url, _depth + 1, _seen,
    )
    return deep_merge(parent_spec, patch)


def find_invalidated_steps(changed_fields: set[str]) -> set[str]:
    """
    BFS forward through the DAG from directly-affected steps.

    A step is *directly affected* when its ``spec_inputs`` intersects
    ``changed_fields``.  A step is *transitively affected* when any of
    its ``step_inputs`` is in the invalidated set.
    """
    # seed: directly affected
    invalidated: set[str] = set()
    for step, defn in PIPELINE_DAG.items():
        if defn.spec_inputs & changed_fields:
            invalidated.add(step)

    # BFS propagation
    queue = deque(invalidated)
    while queue:
        current = queue.popleft()
        for step, defn in PIPELINE_DAG.items():
            if step not in invalidated and current in defn.step_inputs:
                invalidated.add(step)
                queue.append(step)

    return invalidated


# ======================================================================
# Spec patching helpers
# ======================================================================

def deep_merge(base: dict, patch: dict) -> dict:
    """Recursively merge *patch* into a copy of *base*."""
    out = copy.deepcopy(base)
    for key, value in patch.items():
        if (
                key in out
                and isinstance(out[key], dict)
                and isinstance(value, dict)
        ):
            out[key] = deep_merge(out[key], value)
        else:
            out[key] = copy.deepcopy(value)
    return out


def find_changed_fields(
        old: dict[str, Any], new: dict[str, Any]
) -> set[str]:
    """Return the set of top-level keys whose values differ."""
    changed: set[str] = set()
    all_keys = set(old.keys()) | set(new.keys())
    for key in all_keys:
        if key not in old or key not in new:
            changed.add(key)
        elif old[key] != new[key]:
            changed.add(key)
    return changed


# ======================================================================
# Diff computation
# ======================================================================

def _compute_diffs(
        base_cp: PipelineCheckpoint,
        new_cp: PipelineCheckpoint,
        invalidated: set[str],
) -> dict[str, Any]:
    """
    For every invalidated step, load old and new checkpoint data and
    produce a per-variable diff.
    """
    diffs: dict[str, Any] = {}
    for step in sorted(invalidated):
        if step == "load_data":
            continue
        old = base_cp.load(step)
        new = new_cp.load(step)
        if old is None and new is None:
            continue
        step_diff = diff_values(old or {}, new or {})
        if step_diff is not None:
            diffs[step] = step_diff
    return diffs


def diff_values(old: Any, new: Any) -> Optional[dict[str, Any]]:
    """
    Recursively diff two JSON-serialisable values.

    Returns ``None`` when equal; otherwise a dict describing the
    change.  For scalars the dict contains ``old``, ``new``, and
    for numerics ``delta`` + ``delta_pct``.  For dicts/lists it
    recurses and returns only the changed subtree.
    """
    if old == new:
        return None

    # ---- dicts: recurse per key ----
    if isinstance(old, dict) and isinstance(new, dict):
        changes: dict[str, Any] = {}
        for k in sorted(set(old) | set(new)):
            if k not in old:
                changes[k] = {"status": "added", "new": new[k]}
            elif k not in new:
                changes[k] = {"status": "removed", "old": old[k]}
            else:
                sub = diff_values(old[k], new[k])
                if sub is not None:
                    changes[k] = sub
        return changes if changes else None

    # ---- lists of same length: element-wise ----
    if isinstance(old, list) and isinstance(new, list) and len(old) == len(new):
        changes = {}
        for i, (o, n) in enumerate(zip(old, new)):
            sub = diff_values(o, n)
            if sub is not None:
                changes[str(i)] = sub
        if changes:
            return changes
        # lists differ but element-wise comparison found nothing
        # (shouldn't happen, but fall through to leaf diff)

    # ---- leaf values ----
    result: dict[str, Any] = {"old": old, "new": new}
    if (
            isinstance(old, (int, float))
            and isinstance(new, (int, float))
            and old != 0
    ):
        result["delta"] = new - old
        result["delta_pct"] = round((new - old) / abs(old) * 100, 4)
    return result
