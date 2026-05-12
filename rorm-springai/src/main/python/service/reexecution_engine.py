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
            base_spec: dict[str, Any],
            spec_patch: dict[str, Any],
            datasource,
            progress_callback=None,
            new_run_id: str | None = None,
    ) -> dict[str, Any]:
        """
        Re-execute the pipeline with a partial spec change.

        The base spec is supplied by the caller (the swarm holds it in
        memory via its ToolCallRegistry) — this engine no longer reads
        run metadata from Valkey. Only step-level checkpoints under
        ``causal_cp:{base_run_id}:{step}`` are reused server-side.

        Parameters
        ----------
        base_run_id : str
            The base run whose step checkpoints will be reused.
        base_spec : dict
            The full PipelineSpec the base run was launched with (sent
            by the caller; not loaded from storage).
        spec_patch : dict
            Partial PipelineSpec — only the fields that changed.
            Deep-merged into ``base_spec``.
        datasource
            SQL datasource for data loading.
        progress_callback
            Optional ``(pct, msg)`` callback.

        Returns
        -------
        dict with ``run_id``, ``parent_run_id``, ``changed_spec_fields``,
        ``reexecuted_steps``, ``skipped_steps``, ``diffs``, and the
        full pipeline ``result``.
        """
        base_cp = PipelineCheckpoint(base_run_id, self._redis_url)

        # 1. Merge patch → new full spec
        merged = deep_merge(base_spec, spec_patch)

        # 2. Determine which top-level fields actually changed
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

        # 6. Execute pipeline (cached steps are skipped automatically)
        spec = CausalVerificationRequest.from_dict(merged)
        result = self._service.run_pipeline(
            spec, datasource,
            progress_callback=progress_callback,
            checkpoint=new_cp,
        )

        # 7. Compute diffs between base and new for invalidated steps
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
