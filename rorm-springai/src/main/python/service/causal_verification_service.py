"""
Causal Verification Pipeline service.

Translates the notebook-based causal verification pipeline into a
spec-driven service. Each step is parameterized by the PipelineSpec
(CausalVerificationRequest) rather than hardcoded configuration.
"""

import dowhy
import logging
import math
import networkx as nx
import numpy as np
import os
import pandas as pd
import ruptures
import warnings
from concurrent.futures import Future
from dto.causal_verification_request import (
    CausalVerificationRequest,
    EstimationVariant,
    FilterOperator,
    GrfConfig,
    OverlapStrategy,
    RefutationType,
    SlicingMethod,
    StructuralBreakConfig,
    TreatmentForm,
    UnmeasuredMethod,
)
from econml.dml import CausalForestDML, LinearDML
from econml.inference import BootstrapInference
from itertools import combinations
from lightgbm import LGBMClassifier, LGBMRegressor
from scipy.stats import norm, pearsonr, spearmanr
from service.pipeline_dataframe import PipelineDataFrame
from sklearn.linear_model import LinearRegression, LogisticRegression
from sklearn.model_selection import KFold, cross_val_score
from statsmodels.stats.diagnostic import acorr_ljungbox
from statsmodels.stats.outliers_influence import variance_inflation_factor
from statsmodels.stats.stattools import durbin_watson
from typing import Any, Callable, Optional

warnings.filterwarnings("ignore")
logging.getLogger("dowhy.utils.graphviz_plotting").setLevel(logging.CRITICAL)
logging.getLogger("dowhy.graph").setLevel(logging.CRITICAL)
logging.getLogger("dowhy.causal_refuter").setLevel(logging.ERROR)
logger = logging.getLogger(__name__)
logger.info("dowhy=%s  networkx=%s  econml=%s", dowhy.__version__, nx.__version__,
            __import__("econml").__version__)

RANDOM_STATE = 42
CI_ALPHA = 0.05

# -- Adaptive memory parameters --
# Each tunable parameter is defined as (max_val, min_val, threshold_mb).
# Below threshold_mb of DataFrame memory, the parameter stays at max_val.
# Above threshold_mb, it decays asymptotically toward min_val:
#   param = min_val + (max_val - min_val) * threshold / max(threshold, data_mb)

_PARAM_SPECS = {
    "lgbm_n_estimators": (300, 100, 200),
    "bootstrap_samples": (20, 5, 200),
    "refutation_simulations": (10, 3, 200),
    "grf_estimators": (200, 50, 200),
    "grf_min_leaf": (50, 50, 200),  # no reduction
}


def _adaptive_param(name: str, data_mb: float) -> int:
    """Compute a memory-pressure-adapted parameter value."""
    max_val, min_val, threshold = _PARAM_SPECS[name]
    if data_mb <= threshold:
        return max_val
    return int(min_val + (max_val - min_val) * threshold / data_mb)


def _data_mb(data) -> float:
    """Dense float64 footprint of the data in MB."""
    n_rows = len(data)
    n_cols = data.encoded.shape[1] if hasattr(data, 'encoded') else data.shape[1]
    return n_rows * n_cols * 8 / (1024 * 1024)


def _rss_mb() -> float:
    """Current process RSS in MB via /proc/self/statm (kernel, no deps)."""
    try:
        with open("/proc/self/statm") as f:
            # fields: size resident shared text lib data dt (in pages)
            resident_pages = int(f.read().split()[1])
            return resident_pages * os.sysconf("SC_PAGE_SIZE") / (1024 * 1024)
    except (FileNotFoundError, ValueError, OSError):
        try:
            return psutil.Process().memory_info().rss / (1024 * 1024)
        except Exception:
            return 0.0


def _container_limit_mb() -> float:
    """Container memory limit in MB. Reads MemTotal from /proc/meminfo
    which reflects the container's cgroup limit on Linux."""
    try:
        with open("/proc/meminfo") as f:
            for line in f:
                if line.startswith("MemTotal:"):
                    return int(line.split()[1]) / 1024  # kB → MB
    except (FileNotFoundError, ValueError):
        pass
    try:
        return psutil.virtual_memory().total / (1024 * 1024)
    except Exception:
        return 32_000.0


def _reclaim():
    """Force Python GC and return freed pages to OS."""
    import gc
    import ctypes
    gc.collect()
    try:
        ctypes.CDLL("libc.so.6").malloc_trim(0)
    except (OSError, AttributeError):
        pass


class _MemoryBudget:
    """Resolves adaptive parameters and monitors RSS pressure."""

    def __init__(self, data):
        self.data_mb = _data_mb(data)
        self.container_mb = _container_limit_mb()
        self.pressure_ceiling = self.container_mb * 0.75
        logger.info(
            "MemoryBudget: data=%.0f MB, container=%.0f MB, "
            "pressure_ceiling=%.0f MB",
            self.data_mb, self.container_mb, self.pressure_ceiling,
        )

    def param(self, name: str) -> int:
        """Get adaptive parameter, further reduced if RSS is high."""
        base = _adaptive_param(name, self.data_mb)
        rss = _rss_mb()
        if rss > self.pressure_ceiling:
            # Under pressure — halve the parameter (clamped to min)
            min_val = _PARAM_SPECS[name][1]
            reduced = max(min_val, base // 2)
            logger.warning(
                "Memory pressure: RSS=%.0f MB > ceiling=%.0f MB, "
                "reducing %s from %d to %d",
                rss, self.pressure_ceiling, name, base, reduced,
            )
            return reduced
        return base

    def lgbm_defaults(self) -> dict:
        return dict(
            n_estimators=self.param("lgbm_n_estimators"),
            max_depth=6, learning_rate=0.05, verbose=-1,
        )

    def check_and_reclaim(self):
        """If RSS is above 60% of container, force reclaim."""
        rss = _rss_mb()
        if rss > self.container_mb * 0.60:
            logger.info("Pre-submit reclaim: RSS=%.0f MB (%.0f%% of %.0f MB)",
                        rss, rss / self.container_mb * 100, self.container_mb)
            _reclaim()


class CausalVerificationService:

    def __init__(self, db_storage=None, worker_pool=None):
        self._db_storage = db_storage
        self._worker_pool = worker_pool

    def _pool_submit(self, budget: "_MemoryBudget", fn: Callable[[], Any]) -> "Future[Any]":
        """Run fn serially. Check RSS before, reclaim after."""
        budget.check_and_reclaim()
        f: Future = Future()
        try:
            f.set_result(fn())
        except Exception as e:
            f.set_exception(e)
        _reclaim()
        return f

    def run_pipeline(
            self,
            spec: CausalVerificationRequest,
            datasource,
            progress_callback: Optional[Callable] = None,
            checkpoint: Optional["PipelineCheckpoint"] = None,
    ) -> dict[str, Any]:
        """
        Execute the full 13-step causal verification pipeline.

        When *checkpoint* is provided every step's output variables are
        persisted after completion.  On a retry / resume with the same
        ``run_id`` the pipeline skips already-checkpointed steps and
        reloads their variables, except for step 0 (data loading) which
        always re-runs because DataFrames are too large to serialise.
        """
        from datetime import datetime
        from service.pipeline_checkpoint import PipelineCheckpoint  # noqa: F811

        # Register run as in-progress
        if checkpoint:
            checkpoint.save_run_meta({
                "spec": spec.to_dict(),
                "status": "running",
                "started_at": datetime.utcnow().isoformat(),
                "_score": datetime.utcnow().timestamp(),
            })

        result: dict[str, Any] = {"hypothesis_id": spec.hypothesis_id, "steps": {}}

        def report(pct: float, msg: str = ""):
            if progress_callback:
                progress_callback(pct, msg)

        def _cp_load(step: str) -> Optional[dict]:
            return checkpoint.load(step) if checkpoint else None

        def _cp_save(step: str, data: dict) -> None:
            if checkpoint:
                checkpoint.save(step, data)

        # ==================================================================
        # Step 0  —  Load data  (always re-runs; DataFrames not persisted)
        # ==================================================================
        report(0.02, "Loading data")
        data = self._load_data(spec, datasource)
        result["row_count"] = len(data)
        if spec.expected_row_count and len(data) != spec.expected_row_count:
            result["row_count_mismatch"] = {
                "expected": spec.expected_row_count,
                "actual": len(data),
            }

        self._validate_spec(spec, data)
        budget = _MemoryBudget(data)

        # ==================================================================
        # Step 1  —  D-sep refinement
        # ==================================================================
        cp = _cp_load("dsep")
        if cp is not None:
            report(0.05, "D-sep refinement (cached)")
            dsep_result = cp["dsep_result"]
            refined_edges = [tuple(e) for e in cp["refined_edges"]]
        else:
            report(0.05, "D-separation refinement")
            edges = self._parse_dag_edges(spec.dag_edges)
            dsep_result = self._dsep_refinement(data, edges, spec.dsep_threshold)
            refined_edges = dsep_result["refined_edges"]
            _cp_save("dsep", {
                "dsep_result": dsep_result,
                "refined_edges": refined_edges,
            })
        result["steps"]["dsep"] = dsep_result
        dag_nx = self._edges_to_nx(refined_edges)

        # ==================================================================
        # Step 2  —  Identification
        # ==================================================================
        cp = _cp_load("identification")
        if cp is not None:
            report(0.10, "Identification (cached)")
            confounders = cp["confounders"]
            identification_result = cp["identification_result"]
        else:
            report(0.10, "Verifying identification")
            confounders = list(spec.adjustment_set)
            identification_result = {
                "adjustment_set": confounders,
                "mediators_excluded": [m.column for m in spec.mediators_excluded],
            }
            _cp_save("identification", {
                "confounders": confounders,
                "identification_result": identification_result,
            })
        result["steps"]["identification"] = identification_result

        # ==================================================================
        # Step 2b —  Positivity gate (treatment × confounder cell check)
        # ==================================================================
        if spec.positivity_check is not None:
            cp = _cp_load("positivity")
            if cp is not None:
                report(0.12, "Positivity gate (cached)")
                positivity_report = cp["positivity_report"]
                if cp.get("rewritten_variants") is not None:
                    spec = self._apply_positivity_rewrite(spec, cp["rewritten_variants"],
                                                          cp["final_treatment"], cp["surviving_mask"])
                    data = self._apply_positivity_trim(data, cp["surviving_mask"])
            else:
                report(0.12, "Positivity gate")
                positivity_report, rewritten_variants, final_treatment, surviving_mask = \
                    self._positivity_gate(data, spec)
                from dataclasses import asdict
                _cp_save("positivity", {
                    "positivity_report": positivity_report,
                    "rewritten_variants": [asdict(v) for v in rewritten_variants]
                    if rewritten_variants is not None else None,
                    "final_treatment": final_treatment,
                    "surviving_mask": surviving_mask,
                })
                if rewritten_variants is not None:
                    spec = self._apply_positivity_rewrite(spec, rewritten_variants,
                                                          final_treatment, surviving_mask)
                    data = self._apply_positivity_trim(data, surviving_mask)
            result["steps"]["positivity"] = positivity_report
            result["row_count"] = len(data)

        # ==================================================================
        # Step 3  —  Estimation variants (per-variant + coarse checkpoints)
        # ==================================================================
        cp = _cp_load("estimation")
        if cp is not None:
            report(0.15, "Estimation variants (cached)")
            estimation_results = cp["estimation_results"]
            primary_effect = cp["primary_effect"]
            primary_ci = cp.get("primary_ci")
        else:
            report(0.15, "Running estimation variants")
            estimation_results = {}
            variant_futures: dict[str, Future] = {}
            for v in spec.estimation_variants:
                vcp = _cp_load(f"estimation:{v.id}")
                if vcp is not None:
                    estimation_results[v.id] = vcp
                else:
                    variant_futures[v.id] = self._pool_submit(
                        budget, lambda v=v: self._run_estimation_variant(
                            data, v, refined_edges, dag_nx, spec.outcome, budget
                        )
                    )
            for vid, future in variant_futures.items():
                estimation_results[vid] = future.result()
                _cp_save(f"estimation:{vid}", estimation_results[vid])

            primary_effect = estimation_results[spec.estimation_variants[0].id]["effect"]
            primary_ci = estimation_results[spec.estimation_variants[0].id].get("ci")
            _cp_save("estimation", {
                "estimation_results": estimation_results,
                "primary_effect": primary_effect,
                "primary_ci": primary_ci,
            })
        result["steps"]["estimation"] = estimation_results

        if primary_effect is None:
            failed_variants = [vid for vid, r in estimation_results.items()
                               if r.get("effect") is None]
            result["estimation_failure"] = (
                f"Primary variant '{spec.estimation_variants[0].id}' produced no "
                f"effect estimate. Failed variants: {failed_variants}"
            )

        # ==================================================================
        # Step 4  —  Quality gates
        # ==================================================================
        cp = _cp_load("gates")
        if cp is not None:
            report(0.35, "Quality gates (cached)")
            gates_result = cp["gates_result"]
        else:
            report(0.35, "Quality gates")
            gates_result = self._quality_gates(
                data, spec, confounders, primary_effect, primary_ci
            )
            _cp_save("gates", {"gates_result": gates_result})
        result["steps"]["gates"] = gates_result

        # ==================================================================
        # Steps 5-12  —  Parallel execution (independent post-gate steps)
        #
        # These steps share only read-only inputs (data, confounders,
        # refined_edges, estimation_results, primary_effect/ci).
        # Each step is dispatched on a bare thread; heavy DML/GRF leaf
        # work inside each step goes through _pool_submit so the
        # WorkerPool governs memory and concurrency.
        # ==================================================================
        step_outputs: dict[str, Any] = {}

        def _run_cached_step(
                name: str, cp_key: str, cp_extract: Callable,
                compute: Callable, cp_pack: Callable,
        ) -> tuple[str, Any]:
            """Run a step with checkpoint load/save, return (key, value)."""
            cp = _cp_load(cp_key)
            if cp is not None:
                report(0, f"{name} (cached)")
                return cp_key, cp_extract(cp)
            report(0, name)
            val = compute()
            _cp_save(cp_key, cp_pack(val))
            return cp_key, val

        # Heavy steps (DML/GRF) run sequentially to bound peak RSS.
        # Each heavy step gets full pool concurrency for its sub-tasks.
        # Between heavy steps we force GC + malloc_trim to return pages
        # to the OS (glibc doesn't do this automatically).
        # Light analytical steps run together in parallel.

        def _reclaim():
            """Force Python GC and return freed pages to OS."""
            import gc
            import ctypes
            gc.collect()
            try:
                ctypes.CDLL("libc.so.6").malloc_trim(0)
            except (OSError, AttributeError):
                pass  # non-Linux

        # Step 5 — Mediation (1 DML fit)
        if spec.mediation:
            step_outputs["mediation"] = _run_cached_step(
                "Mediation", "mediation",
                lambda cp: cp["mediation_result"],
                lambda: self._mediation(
                    data, spec, confounders, estimation_results, refined_edges),
                lambda v: {"mediation_result": v})[1]
            result["steps"]["mediation"] = step_outputs.get("mediation")
            _reclaim()

        # --- Light steps: run together (no DML, cheap) ---
        light_pending: dict[str, Future] = {}

        if spec.unmeasured_confounding:
            light_pending["unmeasured_confounding"] = self._pool_submit(
                budget, lambda: _run_cached_step(
                    "Unmeasured confounding", "unmeasured_confounding",
                    lambda cp: cp["uc_result"],
                    lambda: self._unmeasured_confounding(data, spec, estimation_results),
                    lambda v: {"uc_result": v}))

        if spec.structural_breaks:
            light_pending["structural_breaks"] = self._pool_submit(
                budget, lambda: _run_cached_step(
                    "Structural breaks", "structural_breaks",
                    lambda cp: cp["breaks_result"],
                    lambda: self._structural_breaks(data, spec, primary_effect, primary_ci),
                    lambda v: {"breaks_result": v}))

        light_pending["range_checks"] = self._pool_submit(
            budget, lambda: _run_cached_step(
                "Range checks", "range_checks",
                lambda cp: cp["range_result"],
                lambda: self._range_checks(data, spec, confounders, estimation_results, budget),
                lambda v: {"range_result": v}))

        for key, f in light_pending.items():
            _, val = f.result()
            step_outputs[key] = val
        del light_pending

        if "unmeasured_confounding" in step_outputs:
            result["steps"]["unmeasured_confounding"] = step_outputs["unmeasured_confounding"]
        if "structural_breaks" in step_outputs:
            result["steps"]["structural_breaks"] = step_outputs["structural_breaks"]
        if "range_checks" in step_outputs:
            result["steps"]["range_checks"] = step_outputs["range_checks"]
        _reclaim()

        # --- Heavy step: Sensitivity (24+ DML fits, full pool concurrency) ---
        _, sensitivity_val = _run_cached_step(
            "Sensitivity", "sensitivity",
            lambda cp: cp["sensitivity_result"],
            lambda: self._sensitivity(
                data, spec, refined_edges, primary_effect, estimation_results, budget),
            lambda v: {"sensitivity_result": v})
        step_outputs["sensitivity"] = sensitivity_val
        result["steps"]["sensitivity"] = sensitivity_val
        _reclaim()

        # --- Heavy step: Refutations (9+ DML fits, full pool concurrency) ---
        if spec.refutations:
            cp = _cp_load("refutations")
            if cp is not None:
                step_outputs["refutations"] = cp["refutations_result"]
            else:
                report(0, "Refutations")
                refute_futures = self._refutations_parallel(
                    data, spec, dag_nx, primary_effect, budget, checkpoint)
                refute_result = self._collect_refutations(
                    refute_futures, spec, primary_effect, checkpoint)
                _cp_save("refutations", {"refutations_result": refute_result})
                step_outputs["refutations"] = refute_result
            if "refutations" in step_outputs:
                result["steps"]["refutations"] = step_outputs["refutations"]
            _reclaim()

        # --- Heavy step: Residual diagnostics (5-fold CV) ---
        _, rd_val = _run_cached_step(
            "Residual diagnostics", "residual_diagnostics",
            lambda cp: cp["residual_result"],
            lambda: self._residual_diagnostics(
                data, spec, confounders, refined_edges, primary_effect, budget),
            lambda v: {
                "residual_result": v,
                "corrected_effect": v.get("corrected_effect"),
            })
        step_outputs["residual_diagnostics"] = rd_val
        result["steps"]["residual_diagnostics"] = rd_val
        corrected = rd_val.get("corrected_effect") if isinstance(rd_val, dict) else None
        if corrected is not None:
            primary_effect = corrected
        _reclaim()

        # --- Heavy step: GRF (3 CausalForestDML, full pool concurrency) ---
        if spec.grf_configs:
            cp = _cp_load("grf")
            if cp is not None:
                step_outputs["grf"] = cp["grf_result"]
            else:
                report(0, "GRF heterogeneity")
                grf_result = self._grf_heterogeneity(data, spec, confounders, budget, checkpoint)
                _cp_save("grf", {"grf_result": grf_result})
                step_outputs["grf"] = grf_result
            result["steps"]["grf"] = step_outputs.get("grf")
            _reclaim()

        # ==============================================================
        # Null-finding diagnostics (when primary CI crosses zero)
        # ==============================================================
        ci_crosses_zero = (
                primary_ci is not None and primary_ci[0] * primary_ci[1] <= 0
        )
        if ci_crosses_zero:
            cp = _cp_load("null_diagnostics")
            if cp is not None:
                report(0.92, "Null diagnostics (cached)")
                result["steps"]["null_diagnostics"] = cp["nd_result"]
            else:
                report(0.92, "Null-finding diagnostics")
                nd_result = self._null_diagnostics(
                    data, spec, confounders,
                    primary_effect, primary_ci, estimation_results,
                    step_outputs.get("sensitivity", {}), budget)
                _cp_save("null_diagnostics", {"nd_result": nd_result})
                result["steps"]["null_diagnostics"] = nd_result

        # ==================================================================
        # Step 13  —  Externalization (depends on GRF from step 6)
        # ==================================================================
        if spec.externalization:
            cp = _cp_load("externalization")
            if cp is not None:
                report(0.95, "Externalization (cached)")
                result["steps"]["externalization"] = cp["ext_result"]
            else:
                report(0.95, "Externalization tests")
                ext_result = self._externalization(data, spec, result)
                _cp_save("externalization", {"ext_result": ext_result})
                result["steps"]["externalization"] = ext_result

        # ==================================================================
        # Final assembly
        # ==================================================================
        result["final_effect"] = primary_effect
        result["ci_crosses_zero"] = bool(
            primary_ci is not None and primary_ci[0] * primary_ci[1] <= 0
        )
        # If auto-correction changed the effect, the original CI is stale
        rd = result.get("steps", {}).get("residual_diagnostics")
        corrected = rd.get("corrected_effect") if isinstance(rd, dict) else None
        if corrected is not None and corrected != primary_ci:
            result["final_ci"] = None
            result["final_ci_note"] = ("CI from primary estimation; final_effect was "
                                       "adjusted by residual auto-correction — "
                                       "recompute CI with the corrected W set")
        else:
            result["final_ci"] = primary_ci
        result["discrepancy_log"] = [
            {"field": d.field_name, "generator_value": d.generator_value,
             "compiler_value": d.compiler_value, "resolution": d.resolution}
            for d in spec.discrepancy_log
        ]

        # Freeze run
        if checkpoint:
            checkpoint.save_run_meta({
                "spec": spec.to_dict(),
                "status": "completed",
                "completed_at": datetime.utcnow().isoformat(),
                "_score": datetime.utcnow().timestamp(),
                "result": result,
            })

        report(1.0, "Pipeline complete")
        return result

    # ------------------------------------------------------------------
    # Step 0: Data loading
    # ------------------------------------------------------------------

    def _load_data(self, spec: CausalVerificationRequest, datasource) -> PipelineDataFrame:
        sql = spec.datasource.sql
        bind_vars = spec.datasource.bind_variables
        result = datasource.fetch(sql, bind_vars)
        data = result.dataframe.to_pandas()
        if spec.strip_columns:
            data = data.drop(
                columns=[c for c in spec.strip_columns if c in data.columns],
                errors="ignore",
            )
        return PipelineDataFrame.from_dataframe(data)

    @staticmethod
    def _validate_spec(spec: CausalVerificationRequest, data: pd.DataFrame) -> None:
        """Validate the full PipelineSpec against the loaded data.

        Raises ValueError with all problems collected into a single message
        so the caller can fix everything in one pass.
        """
        errors: list[str] = []
        cols = set(data.columns)
        nrows = len(data)

        def _require_col(col: str, context: str) -> None:
            if col not in cols:
                errors.append(f"{context}: column '{col}' not found in data. "
                              f"Available: {sorted(cols)}")

        def _require_numeric(col: str, context: str) -> None:
            if col in cols and not pd.api.types.is_numeric_dtype(data.raw[col]):
                errors.append(f"{context}: column '{col}' has dtype "
                              f"'{data.raw[col].dtype}', expected numeric "
                              f"(will be label-encoded but semantically wrong)")

        def _validate_filter(f: "VariantFilter", context: str) -> None:
            if f.and_filters is not None:
                for i, sub in enumerate(f.and_filters):
                    _validate_filter(sub, f"{context}.AND[{i}]")
                return
            if f.or_filters is not None:
                for i, sub in enumerate(f.or_filters):
                    _validate_filter(sub, f"{context}.OR[{i}]")
                return
            if f.not_filter is not None:
                _validate_filter(f.not_filter, f"{context}.NOT")
                return
            # leaf
            if f.column is None:
                errors.append(f"{context}: leaf filter missing 'column'")
                return
            _require_col(f.column, context)
            if f.operator is None:
                errors.append(f"{context}: leaf filter missing 'operator'")
            elif f.operator in (FilterOperator.GT, FilterOperator.LT, FilterOperator.EQ):
                if not f.values:
                    errors.append(f"{context}: operator {f.operator.value} "
                                  f"requires at least one value")
            elif f.operator == FilterOperator.IN:
                if not f.values:
                    errors.append(f"{context}: IN requires a non-empty values list")

        # -- minimum data size (cross_val_score uses cv=5) --
        if nrows < 5:
            errors.append(f"data has {nrows} row(s), need at least 5 "
                          f"for 5-fold cross-validation in quality gates")

        # -- core columns --
        _require_col(spec.treatment, "treatment")
        _require_col(spec.outcome, "outcome")

        # NaN / constant checks on treatment and outcome
        if spec.treatment in cols:
            nan_count = int(data[spec.treatment].isna().sum())
            if nan_count > 0:
                errors.append(f"treatment column '{spec.treatment}' contains "
                              f"{nan_count} NaN value(s); pearsonr / DML will fail")
            if data[spec.treatment].nunique(dropna=True) < 2:
                errors.append(f"treatment column '{spec.treatment}' has fewer than "
                              f"2 unique values; DML estimation requires variance")
        if spec.outcome in cols:
            _require_numeric(spec.outcome, "outcome")
            nan_count = int(data[spec.outcome].isna().sum())
            if nan_count > 0:
                errors.append(f"outcome column '{spec.outcome}' contains "
                              f"{nan_count} NaN value(s); pearsonr / DML will fail")

        # -- adjustment set --
        for c in spec.adjustment_set:
            _require_col(c, "adjustment_set")

        # -- DAG edges: every node must be a column, graph must be acyclic --
        parsed_edges: list[tuple[str, str]] = []
        edges = spec.dag_edges.replace("\n", ";").split(";")
        for edge_str in edges:
            edge_str = edge_str.strip()
            if not edge_str or "->" not in edge_str:
                continue
            src, dst = [s.strip() for s in edge_str.split("->", 1)]
            _require_col(src, f"dag_edges ('{src} -> {dst}')")
            _require_col(dst, f"dag_edges ('{src} -> {dst}')")
            parsed_edges.append((src, dst))

        if not parsed_edges:
            errors.append("dag_edges: no valid edges found (need at least one 'A -> B')")
        else:
            dag = nx.DiGraph(parsed_edges)
            if not nx.is_directed_acyclic_graph(dag):
                errors.append("dag_edges: graph contains a cycle; "
                              "nx.is_d_separator requires a DAG")
            dag_nodes = set(dag.nodes)
            if spec.treatment in cols and spec.treatment not in dag_nodes:
                errors.append(f"dag_edges: treatment '{spec.treatment}' is not "
                              f"a node in the DAG")
            if spec.outcome in cols and spec.outcome not in dag_nodes:
                errors.append(f"dag_edges: outcome '{spec.outcome}' is not "
                              f"a node in the DAG")

        # -- dsep_threshold --
        if spec.dsep_threshold <= 0:
            errors.append(f"dsep_threshold must be positive, got {spec.dsep_threshold}")

        # -- estimation variants --
        if not spec.estimation_variants:
            errors.append("estimation_variants must not be empty "
                          "(at least one variant is required)")

        variant_ids: list[str] = []
        for v in spec.estimation_variants:
            variant_ids.append(v.id)
            _require_col(v.treatment_column, f"estimation_variant '{v.id}' treatment_column")
            for c in v.w_columns:
                _require_col(c, f"estimation_variant '{v.id}' w_columns")
            if v.treatment_form == TreatmentForm.BINARY_THRESHOLD:
                if v.threshold_value is None:
                    errors.append(f"estimation_variant '{v.id}': BINARY_THRESHOLD "
                                  f"requires a numeric threshold_value")
                elif not isinstance(v.threshold_value, (int, float)):
                    errors.append(f"estimation_variant '{v.id}': threshold_value must be "
                                  f"numeric, got {type(v.threshold_value).__name__} "
                                  f"'{v.threshold_value}'")
            if v.filter:
                _validate_filter(v.filter, f"estimation_variant '{v.id}' filter")

        # duplicate variant IDs
        seen: set[str] = set()
        for vid in variant_ids:
            if vid in seen:
                errors.append(f"duplicate estimation_variant id: '{vid}'")
            seen.add(vid)

        # -- mediators_excluded --
        for m in spec.mediators_excluded:
            _require_col(m.column, "mediators_excluded")
            if m.direct_effect_variant_id not in seen:
                errors.append(f"mediators_excluded '{m.column}': "
                              f"direct_effect_variant_id '{m.direct_effect_variant_id}' "
                              f"does not match any estimation variant")

        # -- mediation --
        if spec.mediation:
            for med in spec.mediation:
                _require_col(med.mediator, f"mediation '{med.mediator}'")
                if med.total_variant_id not in seen:
                    errors.append(f"mediation '{med.mediator}': total_variant_id "
                                  f"'{med.total_variant_id}' not found")
                if med.direct_variant_id not in seen:
                    errors.append(f"mediation '{med.mediator}': direct_variant_id "
                                  f"'{med.direct_variant_id}' not found")

        # -- GRF configs --
        for cfg in spec.grf_configs:
            for c in cfg.modifier_columns:
                _require_col(c, f"grf_config '{cfg.id}' modifier_columns")
            for col in cfg.slicing:
                _require_col(col, f"grf_config '{cfg.id}' slicing")
                if cfg.slicing[col] not in ("unique", "quartile"):
                    errors.append(f"grf_config '{cfg.id}' slicing['{col}']: "
                                  f"method must be 'unique' or 'quartile', "
                                  f"got '{cfg.slicing[col]}'")

        # -- quality gates value ranges --
        g = spec.gates
        if not (0 <= g.nuisance_r2.outcome_abort <= 1):
            errors.append(f"gates.nuisance_r2.outcome_abort must be in [0,1], "
                          f"got {g.nuisance_r2.outcome_abort}")
        if not (0 <= g.nuisance_r2.outcome_flag <= 1):
            errors.append(f"gates.nuisance_r2.outcome_flag must be in [0,1], "
                          f"got {g.nuisance_r2.outcome_flag}")
        if not (0 <= g.nuisance_r2.treatment_abort <= 1):
            errors.append(f"gates.nuisance_r2.treatment_abort must be in [0,1], "
                          f"got {g.nuisance_r2.treatment_abort}")
        if not (0 <= g.nuisance_r2.treatment_flag <= 1):
            errors.append(f"gates.nuisance_r2.treatment_flag must be in [0,1], "
                          f"got {g.nuisance_r2.treatment_flag}")
        if not (0 <= g.nuisance_r2.treatment_structural_max_r2 <= 1):
            errors.append(f"gates.nuisance_r2.treatment_structural_max_r2 must be "
                          f"in [0,1], got {g.nuisance_r2.treatment_structural_max_r2}")
        if g.sanity.expected_direction not in (-1, 1):
            errors.append(f"gates.sanity.expected_direction must be -1 or 1, "
                          f"got {g.sanity.expected_direction}")

        # -- sensitivity --
        for drop in spec.sensitivity.confounder_drops:
            _require_col(drop.column, "sensitivity.confounder_drops")
            if drop.deviation_threshold_pct <= 0:
                errors.append(f"sensitivity.confounder_drops '{drop.column}': "
                              f"deviation_threshold_pct must be positive, "
                              f"got {drop.deviation_threshold_pct}")
        for add in spec.sensitivity.confounder_adds:
            _require_col(add.column, "sensitivity.confounder_adds")
        for tv in spec.sensitivity.threshold_variants:
            if not isinstance(tv.threshold, (int, float)):
                errors.append(f"sensitivity.threshold_variants: threshold must be "
                              f"numeric, got '{tv.threshold}'")
            else:
                _require_numeric(spec.treatment,
                                 f"sensitivity.threshold_variants (threshold={tv.threshold}): "
                                 f"treatment")
        for mv in spec.sensitivity.model_variants:
            if mv.primary_variant_id not in seen:
                errors.append(f"sensitivity.model_variants: primary_variant_id "
                              f"'{mv.primary_variant_id}' not found")

        # -- structural breaks --
        valid_period_freqs = {"D", "W", "M", "Q", "Y"}
        for sb in spec.structural_breaks:
            _require_col(sb.entity_column, f"structural_break '{sb.id}' entity_column")
            _require_col(sb.temporal_column, f"structural_break '{sb.id}' temporal_column")
            if sb.pelt_penalty <= 0:
                errors.append(f"structural_break '{sb.id}': pelt_penalty must be "
                              f"positive, got {sb.pelt_penalty}")
            if sb.min_obs_per_period <= 0:
                errors.append(f"structural_break '{sb.id}': min_obs_per_period must "
                              f"be positive, got {sb.min_obs_per_period}")
            grain_char = sb.temporal_grain[0].upper() if sb.temporal_grain else ""
            if grain_char not in valid_period_freqs:
                errors.append(f"structural_break '{sb.id}': temporal_grain "
                              f"'{sb.temporal_grain}' is not a valid pandas period "
                              f"frequency (expected one starting with D/W/M/Q/Y)")

        # -- residual checks --
        for ac in spec.residual_checks.autocorrelation:
            _require_col(ac.temporal_column, "residual_checks.autocorrelation temporal_column")
            if not ac.lags:
                errors.append("residual_checks.autocorrelation: lags must not be empty")
            else:
                if any(lag <= 0 for lag in ac.lags):
                    errors.append(f"residual_checks.autocorrelation: all lags must "
                                  f"be positive integers, got {ac.lags}")
                max_lag = max(ac.lags)
                if max_lag >= nrows:
                    errors.append(f"residual_checks.autocorrelation: max lag "
                                  f"{max_lag} must be < data length {nrows} "
                                  f"(acorr_ljungbox shape mismatch)")
            if ac.threshold <= 0:
                errors.append(f"residual_checks.autocorrelation: threshold must "
                              f"be positive, got {ac.threshold}")
        if spec.residual_checks.field_correlation.threshold <= 0:
            errors.append("residual_checks.field_correlation.threshold must be "
                          f"positive, got {spec.residual_checks.field_correlation.threshold}")
        for c in spec.residual_checks.field_correlation.check_columns:
            _require_col(c, "residual_checks.field_correlation.check_columns")
        if spec.residual_checks.auto_correction.max_iterations <= 0:
            errors.append("residual_checks.auto_correction.max_iterations must be "
                          f"positive, got {spec.residual_checks.auto_correction.max_iterations}")
        for mc in spec.residual_checks.metadata_correlation:
            _require_col(mc.column, "residual_checks.metadata_correlation")

        # -- range checks --
        for ov in spec.range_checks.overlap:
            if ov.variant_id not in seen:
                errors.append(f"range_checks.overlap: variant_id "
                              f"'{ov.variant_id}' not found")
            if not (0 < ov.threshold <= 1):
                errors.append(f"range_checks.overlap '{ov.variant_id}': "
                              f"threshold must be in (0,1], got {ov.threshold}")
            if ov.response_strategy == OverlapStrategy.TRIM:
                if ov.trim_bounds is None or len(ov.trim_bounds) != 2:
                    errors.append(f"range_checks.overlap '{ov.variant_id}': "
                                  f"TRIM strategy requires trim_bounds with "
                                  f"exactly 2 values [low, high]")
                elif ov.trim_bounds[0] >= ov.trim_bounds[1]:
                    errors.append(f"range_checks.overlap '{ov.variant_id}': "
                                  f"trim_bounds[0] must be < trim_bounds[1], "
                                  f"got {ov.trim_bounds}")
        for vc in spec.range_checks.variance:
            _require_col(vc.column, "range_checks.variance")

        # -- unmeasured confounding --
        for uc in spec.unmeasured_confounding:
            if uc.variant_id not in seen:
                errors.append(f"unmeasured_confounding: variant_id "
                              f"'{uc.variant_id}' not found")

        # -- externalization --
        if spec.externalization:
            for ab in spec.externalization.allocation_bias:
                _require_col(ab.treatment_column, "externalization.allocation_bias treatment_column")
                _require_col(ab.grouping_column, "externalization.allocation_bias grouping_column")
            from service.tier_ordering import parse_ordering
            for dr in spec.externalization.domain_rankings:
                if not dr.ordering or not dr.ordering.strip():
                    errors.append(f"externalization.domain_rankings '{dr.source}': "
                                  f"ordering is empty")
                else:
                    try:
                        parse_ordering(dr.ordering)
                    except ValueError as e:
                        errors.append(f"externalization.domain_rankings '{dr.source}': "
                                      f"invalid ordering notation: {e}")
                if not isinstance(dr.expected_concordance, (int, float)):
                    errors.append(f"externalization.domain_rankings '{dr.source}': "
                                  f"expected_concordance must be numeric "
                                  f"(fraction of pairwise assertions, 0.0-1.0), "
                                  f"got {type(dr.expected_concordance).__name__}")
                elif not (0.0 <= dr.expected_concordance <= 1.0):
                    errors.append(f"externalization.domain_rankings '{dr.source}': "
                                  f"expected_concordance must be in [0, 1], "
                                  f"got {dr.expected_concordance}")

        if errors:
            raise ValueError(
                f"Pipeline spec validation failed ({len(errors)} error(s)):\n"
                + "\n".join(f"  • {e}" for e in errors)
            )

    # ------------------------------------------------------------------
    # Step 1: D-sep refinement
    # ------------------------------------------------------------------

    def _dsep_refinement(
            self, data: pd.DataFrame, edges: list[tuple[str, str]], threshold: float
    ) -> dict:
        all_nodes = list({n for e in edges for n in e})
        violations, confirmed = self._run_dsep_tests(data, edges, all_nodes, threshold)
        refined_edges, fallback = self._add_fallback_edges(edges, violations)
        return {
            "broad_edge_count": len(edges),
            "refined_edge_count": len(refined_edges),
            "violation_count": len(violations),
            "confirmed_count": len(confirmed),
            "fallback_edges": [list(e) for e in fallback],
            "refined_edges": refined_edges,
            "violations": [
                {"node_a": v["node_a"], "node_b": v["node_b"],
                 "correlation": v["correlation"], "p_value": v["p_value"]}
                for v in violations
            ],
        }

    def _run_dsep_tests(self, data, edges, all_nodes, threshold):
        implications = self._get_dsep_implications(edges, all_nodes)
        buffer = {}
        for a, b, cond in implications:
            r = self._test_ci(data, a, b, cond)
            buffer[(a, b, frozenset(cond))] = r
        max_r = max((abs(r["correlation"]) for r in buffer.values()), default=0)
        violations, confirmed = [], []
        for (a, b, _), r in buffer.items():
            if r["p_value"] < CI_ALPHA and abs(r["correlation"]) > max_r ** 1.5:
                violations.append(r)
            else:
                confirmed.append(r)
        return violations, confirmed

    @staticmethod
    def _get_dsep_implications(edges, all_nodes):
        G = nx.DiGraph(edges)
        seen, unique = set(), []
        for a, b in combinations(all_nodes, 2):
            if G.has_edge(a, b) or G.has_edge(b, a):
                continue
            parents = set(G.predecessors(a)) | set(G.predecessors(b)) - {a, b}
            for cond in [parents, set()]:
                if nx.is_d_separator(G, {a}, {b}, cond):
                    key = (min(a, b), max(a, b), tuple(sorted(cond)))
                    if key not in seen:
                        seen.add(key)
                        unique.append((a, b, cond))
        return unique

    @staticmethod
    def _test_ci(data, a, b, cond_set):
        enc = data.encoded
        a_vals = enc[a].values.astype(float)
        b_vals = enc[b].values.astype(float)
        if not cond_set:
            r, p = pearsonr(a_vals, b_vals)
        else:
            X = enc[list(cond_set)].values.astype(float)
            res_a = a_vals - LinearRegression().fit(X, a_vals).predict(X)
            res_b = b_vals - LinearRegression().fit(X, b_vals).predict(X)
            r, p = pearsonr(res_a, res_b)
        return {"node_a": a, "node_b": b, "cond_set": list(cond_set),
                "correlation": float(r), "p_value": float(p)}

    @staticmethod
    def _add_fallback_edges(current_edges, violations):
        added = []
        edges = list(current_edges)
        for v in violations:
            a, b = v["node_a"], v["node_b"]
            if (a, b) in edges or (b, a) in edges:
                continue
            G_temp = nx.DiGraph(edges)
            a_d = len(nx.ancestors(G_temp, a)) if a in G_temp else 0
            b_d = len(nx.ancestors(G_temp, b)) if b in G_temp else 0
            edge = (a, b) if a_d <= b_d else (b, a)
            G_test = nx.DiGraph(edges + [edge])
            if nx.is_directed_acyclic_graph(G_test):
                edges.append(edge)
                added.append(edge)
        return edges, added

    # ------------------------------------------------------------------
    # Step 3: Estimation
    # ------------------------------------------------------------------

    def _run_estimation_variant(
            self,
            data: pd.DataFrame,
            variant: EstimationVariant,
            refined_edges: list[tuple[str, str]],
            dag_nx: str,
            outcome_col: str,
            budget: "_MemoryBudget" = None,
    ) -> dict[str, Any]:
        filtered = self._apply_variant_filter(data, variant)
        df = filtered.encoded.copy()
        treatment_col = variant.treatment_column
        W_cols = variant.w_columns
        discrete = variant.treatment_form != TreatmentForm.CONTINUOUS

        # Re-map treatment to contiguous 0..n after filtering so EconML's
        # internal OneHotEncoder doesn't encounter gaps in category codes.
        # Build code→label mapping for per-category effect labeling.
        code_to_label: dict[int, str] = {}
        if discrete and treatment_col in df.columns:
            raw_vals = sorted(filtered.raw[treatment_col].dropna().unique())
            df[treatment_col] = df[treatment_col].astype("category").cat.codes
            codes = sorted(df[treatment_col].dropna().unique())
            for code, label in zip(codes, raw_vals):
                code_to_label[int(code)] = str(label)

        if variant.treatment_form == TreatmentForm.BINARY_THRESHOLD and variant.threshold_value is not None:
            bin_col = f"_bin_{treatment_col}_{variant.threshold_value}"
            df[bin_col] = (df[treatment_col] > variant.threshold_value).astype(int)
            treatment_col = bin_col
            refined_edges = [
                (bin_col if s == variant.treatment_column else s,
                 bin_col if d == variant.treatment_column else d)
                for s, d in refined_edges
            ]

        return self._estimate_dml(
            df, variant, W_cols, treatment_col, discrete, refined_edges, outcome_col,
            code_to_label, budget,
        )

    def _estimate_dml(
            self,
            df: pd.DataFrame,
            variant: EstimationVariant,
            w_cols: list[str],
            treatment_col: str,
            discrete: bool,
            refined_edges: list[tuple[str, str]],
            outcome_col: str,
            code_to_label: dict[int, str] | None = None,
            budget: "_MemoryBudget" = None,
    ) -> dict[str, Any]:
        """Run DML estimation directly via econml (bypasses DoWhy graph layer)."""
        try:
            Y = df[outcome_col].values
            T = df[treatment_col].values
            W = df[w_cols].values

            lgbm_kw = budget.lgbm_defaults() if budget else dict(
                n_estimators=300, max_depth=6, learning_rate=0.05, verbose=-1)
            n_bootstrap = budget.param("bootstrap_samples") if budget else 20
            model_t = LGBMClassifier(**lgbm_kw) if discrete else LGBMRegressor(**lgbm_kw)
            dml = LinearDML(
                model_y=LGBMRegressor(**lgbm_kw),
                model_t=model_t,
                discrete_treatment=discrete,
            )
            dml.fit(Y, T, W=W,
                    inference=BootstrapInference(
                        n_bootstrap_samples=n_bootstrap, n_jobs=1))

            raw_effect = dml.effect()
            effect = float(raw_effect.mean())
            ci_lo, ci_hi = dml.effect_interval(alpha=CI_ALPHA)
            ci = (float(ci_lo.mean()), float(ci_hi.mean()))

            # Per-category effects for discrete treatments:
            # const_marginal_effect() returns (n, d_t) where d_t = n_categories - 1
            category_effects = None
            if discrete and code_to_label:
                cme = dml.const_marginal_effect()
                if cme.ndim == 1:
                    cme = cme.reshape(-1, 1)
                logger.info("Per-category: cme_shape=%s, code_to_label=%s",
                            cme.shape, code_to_label)
                codes = sorted(code_to_label.keys())
                ref_label = code_to_label.get(codes[0], str(codes[0]))
                non_ref_codes = codes[1:]
                col_name = variant.treatment_column
                category_effects = {}
                for i, code in enumerate(non_ref_codes):
                    if i < cme.shape[1]:
                        label = code_to_label.get(code, str(code))
                        category_effects[f"{col_name}={label}"] = float(cme[:, i].mean())
                category_effects[f"{col_name}={ref_label}"] = 0.0

        except Exception as e:
            logger.warning(f"Variant {variant.id} estimation failed: {e}")
            return {"variant_id": variant.id, "effect": None, "ci": None, "error": str(e)}

        result = {
            "variant_id": variant.id,
            "effect": effect,
            "ci": ci,
            "treatment_column": treatment_col,
            "w_columns": w_cols,
            "n_obs": len(df),
            "discrete": discrete,
        }
        if category_effects is not None:
            result["category_effects"] = category_effects
        return result

    # ------------------------------------------------------------------
    # Step 4: Quality gates
    # ------------------------------------------------------------------

    def _quality_gates(
            self, data, spec, confounders, effect, ci
    ) -> dict[str, Any]:
        gates = spec.gates
        result: dict[str, Any] = {}
        enc = data.encoded

        # Nuisance R2
        outcome_r2 = float(np.mean(cross_val_score(
            LGBMRegressor(**LGBM_DEFAULTS), enc[confounders], enc[spec.outcome],
            cv=5, scoring="r2")))
        treatment_r2 = float(np.mean(cross_val_score(
            LGBMRegressor(**LGBM_DEFAULTS), enc[confounders], enc[spec.treatment],
            cv=5, scoring="r2")))

        result["nuisance_r2"] = {
            "outcome_r2": outcome_r2,
            "treatment_r2": treatment_r2,
            "outcome_status": "flag" if outcome_r2 < gates.nuisance_r2.outcome_flag
            else "pass",
            "treatment_status": "flag" if treatment_r2 < gates.nuisance_r2.treatment_flag
            else "structural" if treatment_r2 > gates.nuisance_r2.treatment_structural_max_r2
            else "pass",
        }

        # Sanity
        if effect is not None:
            direction_ok = np.sign(effect) == gates.sanity.expected_direction
            magnitude = abs(effect)
            result["sanity"] = {
                "direction_ok": direction_ok,
                "effect_magnitude": magnitude,
                "flag_magnitude": gates.sanity.flag_magnitude,
                "status": "flag" if magnitude > gates.sanity.flag_magnitude
                else "pass",
            }
            if not direction_ok:
                result["sanity"]["warning"] = "Effect direction does not match expected"

        return result

    # ------------------------------------------------------------------
    # Step 2b: Positivity gate
    # ------------------------------------------------------------------

    def _positivity_gate(self, data, spec):
        """Check treatment × confounder cell sizes, coarsen if needed.

        Returns (positivity_report, rewritten_variants_or_None,
                 final_treatment_or_None, surviving_mask_or_None).
        """
        pc = spec.positivity_check
        hierarchy = pc.treatment_hierarchy
        confounder = pc.confounder_column
        attempted_levels = []

        for level_idx, treatment_col in enumerate(hierarchy):
            if treatment_col not in data.columns:
                attempted_levels.append({
                    "level": treatment_col,
                    "error": f"Column '{treatment_col}' not in data",
                    "verdict": "SKIPPED",
                })
                continue

            treatment_vals = data.raw[treatment_col] if treatment_col in data.raw.columns \
                else data.encoded[treatment_col]
            confounder_vals = data.raw[confounder] if confounder in data.raw.columns \
                else data.encoded[confounder]

            ct = pd.crosstab(treatment_vals, confounder_vals)
            n_cells = ct.size
            sparse_cells = []
            sparse_mask = pd.Series(False, index=data.raw.index)

            for t_val in ct.index:
                for c_val in ct.columns:
                    count = int(ct.loc[t_val, c_val])
                    if count < pc.min_cell_threshold:
                        sparse_cells.append({
                            "treatment": str(t_val),
                            "confounder": str(c_val),
                            "count": count,
                        })
                        sparse_mask |= (
                                (treatment_vals == t_val) & (confounder_vals == c_val)
                        )

            surviving_mask = ~sparse_mask
            surviving_n = int(surviving_mask.sum())
            total_n = len(data)
            coverage_pct = surviving_n / total_n * 100

            level_report = {
                "level": treatment_col,
                "n_cells": n_cells,
                "sparse_cells_count": len(sparse_cells),
                "sparse_cells": sparse_cells[:50],
                "coverage_pct": round(coverage_pct, 2),
            }

            if coverage_pct >= pc.min_coverage_pct:
                level_report["verdict"] = "PASSED"
                attempted_levels.append(level_report)

                if level_idx == 0 and len(sparse_cells) == 0:
                    # No trimming needed at finest level
                    report = {
                        "attempted_levels": attempted_levels,
                        "final_level": treatment_col,
                        "original_n": total_n,
                        "surviving_n": total_n,
                    }
                    return report, None, None, None

                # Trim sparse cells and possibly rewrite variants
                trimmed_cells = sparse_cells
                rewritten = self._rewrite_variants_for_positivity(
                    spec, treatment_col, hierarchy[0] if level_idx > 0 else None,
                    data, surviving_mask)
                report = {
                    "attempted_levels": attempted_levels,
                    "final_level": treatment_col,
                    "trimmed_cells": trimmed_cells[:100],
                    "original_n": total_n,
                    "surviving_n": surviving_n,
                }
                return report, rewritten, treatment_col, surviving_mask.tolist()
            else:
                level_report["verdict"] = "COARSENED"
                attempted_levels.append(level_report)

        # Hierarchy exhausted
        last_level = attempted_levels[-1] if attempted_levels else None
        report = {
            "attempted_levels": attempted_levels,
            "final_level": None,
            "original_n": len(data),
            "surviving_n": 0,
            "failure": (
                f"Positivity violation at all granularity levels. "
                f"Finest surviving: {last_level['level'] if last_level else 'none'} "
                f"at {last_level['coverage_pct'] if last_level else 0}% coverage."
            ),
        }
        return report, None, None, None

    def _rewrite_variants_for_positivity(self, spec, final_treatment, original_treatment,
                                         data, surviving_mask):
        """Rewrite estimation variants for coarsened treatment level."""
        from copy import deepcopy
        from dataclasses import replace as dc_replace
        coarsened = original_treatment is not None and final_treatment != original_treatment

        rewritten = []
        for v in spec.estimation_variants:
            if not coarsened:
                # Same treatment level, just trimming sparse cells
                rewritten.append(v)
                continue

            # Check if this is a binary filter variant
            if v.filter is not None:
                filt = v.filter
                if hasattr(filt, 'values') and filt.values:
                    # Map fine-level filter values to coarse level
                    surviving_data = data.raw[surviving_mask]
                    fine_to_coarse = {}
                    if original_treatment in surviving_data.columns and final_treatment in surviving_data.columns:
                        mapping = surviving_data[[original_treatment, final_treatment]].drop_duplicates()
                        for _, row in mapping.iterrows():
                            fine_to_coarse[str(row[original_treatment])] = str(row[final_treatment])

                    coarse_values = set()
                    for fv in filt.values:
                        mapped = fine_to_coarse.get(str(fv))
                        if mapped:
                            coarse_values.add(mapped)

                    if len(coarse_values) < 2:
                        # Both levels map to same coarse value or one was trimmed → drop
                        continue

                    new_v = dc_replace(v,
                                       treatment_column=final_treatment,
                                       filter=dc_replace(filt,
                                                         column=final_treatment,
                                                         values=sorted(coarse_values)),
                                       reference_category=sorted(coarse_values)[0])
                    rewritten.append(new_v)
                else:
                    rewritten.append(dc_replace(v, treatment_column=final_treatment))
            else:
                # Primary/nonparam/direct variants: just rewrite treatment column
                rewritten.append(dc_replace(v, treatment_column=final_treatment))

        return rewritten

    @staticmethod
    def _apply_positivity_trim(data, surviving_mask):
        """Return trimmed PipelineDataFrame."""
        mask = pd.Series(surviving_mask, index=data.raw.index) if isinstance(surviving_mask, list) \
            else surviving_mask
        return data.filter_mask(mask)

    def _apply_positivity_rewrite(self, spec, rewritten_variants, final_treatment, surviving_mask):
        """Return spec with rewritten estimation variants and treatment."""
        from dataclasses import replace as dc_replace

        new_variants = []
        for v in rewritten_variants:
            if isinstance(v, dict):
                from dto.causal_verification_request import EstimationVariant
                new_variants.append(EstimationVariant.from_dict(v))
            else:
                new_variants.append(v)

        new_spec = dc_replace(spec,
                              treatment=final_treatment if final_treatment else spec.treatment,
                              estimation_variants=new_variants)
        return new_spec

    # ------------------------------------------------------------------
    # Step 5: Mediation
    # ------------------------------------------------------------------

    def _mediation(
            self, data, spec, confounders, estimation_results, refined_edges
    ) -> list[dict]:
        results = []
        for med in spec.mediation:
            total_est = estimation_results.get(med.total_variant_id, {})
            total_effect = total_est.get("effect")
            if total_effect is None:
                results.append({"mediator": med.mediator, "error": "total variant not found"})
                continue

            enc = data.encoded
            discrete = spec.treatment_form != TreatmentForm.CONTINUOUS
            W_med = enc[confounders + [med.mediator]].values
            try:
                model_t = LGBMClassifier(**LGBM_DEFAULTS) if discrete else LGBMRegressor(**LGBM_DEFAULTS)
                dml = LinearDML(
                    model_y=LGBMRegressor(**LGBM_DEFAULTS),
                    model_t=model_t,
                    discrete_treatment=discrete,
                )
                dml.fit(enc[spec.outcome].values, enc[spec.treatment].values, W=W_med)
                direct = float(dml.effect().mean())
                mediated = total_effect - direct
                fraction = mediated / total_effect if total_effect != 0 else 0
                results.append({
                    "mediator": med.mediator,
                    "pathway": med.pathway,
                    "direct_effect": direct,
                    "mediated_effect": mediated,
                    "fraction": fraction,
                })
            except Exception as e:
                results.append({"mediator": med.mediator, "error": str(e)})
        return results

    # ------------------------------------------------------------------
    # Step 6: GRF heterogeneity
    # ------------------------------------------------------------------

    def _grf_heterogeneity(self, data, spec, confounders,
                           budget=None, checkpoint=None) -> list[dict]:
        enc = data.encoded

        def _fit_one_grf(cfg: GrfConfig) -> dict:
            try:
                X_grf = enc[cfg.modifier_columns].values
                Y = enc[spec.outcome].values
                T = enc[spec.treatment].values
                W = enc[confounders].values

                grf = CausalForestDML(
                    model_y=LGBMRegressor(**LGBM_DEFAULTS),
                    model_t=LGBMRegressor(**LGBM_DEFAULTS),
                    n_estimators=GRF_ESTIMATORS,
                    min_samples_leaf=GRF_MIN_LEAF,
                    random_state=RANDOM_STATE,
                )
                grf.fit(Y=Y, T=T, X=X_grf, W=W)
                cates = grf.effect(X=X_grf)

                slices = {}
                for col, method in cfg.slicing.items():
                    if method == "unique":
                        for val in sorted(data[col].unique()):
                            subset = cates[data[col] == val]
                            slices[f"{col}={val}"] = {
                                "mean_cate": float(subset.mean()),
                                "std_cate": float(subset.std()),
                                "n": int(len(subset)),
                            }
                    elif method == "quartile":
                        q_col = pd.qcut(data[col], 4, duplicates="drop")
                        for q, idx in data.groupby(q_col).groups.items():
                            slices[f"{col}={q}"] = {
                                "mean_cate": float(cates[idx].mean()),
                                "std_cate": float(cates[idx].std()),
                                "n": int(len(idx)),
                            }

                importances = {
                    name: float(imp)
                    for name, imp in zip(cfg.modifier_columns, grf.feature_importances_)
                }
                return {
                    "config_id": cfg.id,
                    "slices": slices,
                    "feature_importances": importances,
                    "mean_cate": float(cates.mean()),
                    "std_cate": float(cates.std()),
                }
            except Exception as e:
                return {"config_id": cfg.id, "error": str(e)}

        mem = self._mem_estimate(data, factor=15)
        results = []
        futures: dict[str, Future] = {}
        for cfg in spec.grf_configs:
            cached = checkpoint.load(f"grf:{cfg.id}") if checkpoint else None
            if cached is not None:
                results.append(cached)
            else:
                futures[cfg.id] = self._pool_submit(mem, lambda c=cfg: _fit_one_grf(c))
        for cid, f in futures.items():
            r = f.result()
            results.append(r)
            if checkpoint:
                checkpoint.save(f"grf:{cid}", r)
        return results

    # ------------------------------------------------------------------
    # Step 7: Refutations
    # ------------------------------------------------------------------

    def _refutations_parallel(self, data, spec, dag_nx, primary_effect,
                              budget, checkpoint=None) -> dict[str, Future]:
        """Submit each refutation type as an independent pool task.

        Per-type checkpoints: ``refutation:{type}`` keys.  Cached types
        are wrapped in already-resolved futures.
        """
        discrete = spec.treatment_form != TreatmentForm.CONTINUOUS

        def _build_model():
            model_t = LGBMClassifier(**LGBM_DEFAULTS) if discrete else LGBMRegressor(**LGBM_DEFAULTS)
            params = {
                "init_params": {
                    "model_y": LGBMRegressor(**LGBM_DEFAULTS),
                    "model_t": model_t,
                    "model_final": LinearRegression(),
                    "discrete_treatment": discrete,
                },
                "fit_params": {},
            }
            model = dowhy.CausalModel(
                data=data.encoded, treatment=spec.treatment,
                outcome=spec.outcome, graph=dag_nx,
                effect_modifiers=[],
            )
            ident = model.identify_effect(proceed_when_unidentifiable=False)
            est = model.estimate_effect(
                ident, method_name="backdoor.econml.dml.DML",
                method_params=params,
            )
            return model, ident, est

        def _run_refutation(ref_type, method_name, **kwargs):
            model, ident, est = _build_model()
            r = model.refute_estimate(ident, est, method_name=method_name,
                                      num_simulations=REFUTATION_SIMULATIONS, **kwargs)
            return ref_type, r

        dispatch = {
            RefutationType.PLACEBO: lambda: _run_refutation(
                "placebo", "placebo_treatment_refuter", placebo_type="permute"),
            RefutationType.RANDOM_CAUSE: lambda: _run_refutation(
                "random_cause", "random_common_cause"),
            RefutationType.SUBSET: lambda: _run_refutation(
                "subset", "data_subset_refuter", subset_fraction=0.8),
            RefutationType.TEMPORAL_PLACEBO: lambda: (
                "temporal_placebo",
                self._temporal_placebo(data, spec, dag_nx, primary_effect)),
        }

        futures: dict[str, Future] = {}
        for ref_cfg in spec.refutations:
            key = ref_cfg.type.value.lower()
            cached = checkpoint.load(f"refutation:{key}") if checkpoint else None
            if cached is not None:
                f: Future = Future()
                f.set_result(("_cached", cached))
                futures[key] = f
            elif ref_cfg.type in dispatch:
                # Run inline — no pool wrapper. Temporal placebo submits
                # sub-tasks to the pool internally; wrapping it in another
                # pool task would double-count memory and risk deadlock.
                f: Future = Future()
                try:
                    f.set_result(dispatch[ref_cfg.type]())
                except Exception as e:
                    f.set_exception(e)
                futures[key] = f
        return futures

    def _collect_refutations(self, futures: dict[str, Future],
                             spec, primary_effect, checkpoint=None) -> dict[str, Any]:
        results = {}
        for key, f in futures.items():
            try:
                name, r = f.result()
                if name == "_cached":
                    results[key] = r
                    continue
                if name == "placebo":
                    ratio = abs(r.new_effect) / abs(primary_effect) if primary_effect else 0
                    entry = {
                        "new_effect": float(r.new_effect),
                        "ratio": float(ratio),
                        "flag": ratio > spec.gates.placebo.flag_ratio,
                    }
                elif name in ("random_cause", "subset"):
                    shift = (abs(r.new_effect - primary_effect) / abs(primary_effect)
                             if primary_effect else 0)
                    entry = {
                        "new_effect": float(r.new_effect),
                        "shift_pct": float(shift),
                    }
                elif name == "temporal_placebo":
                    entry = r
                else:
                    entry = {"error": f"unknown refutation type: {name}"}
                results[name] = entry
                if checkpoint:
                    checkpoint.save(f"refutation:{name}", entry)
            except Exception as e:
                results[key] = {"error": str(e)}
        return results

    def _temporal_placebo(
            self, data: pd.DataFrame, spec: CausalVerificationRequest,
            dag_nx: str, primary_effect: float,
    ) -> dict[str, Any]:
        """Temporal placebo: shift treatment in both time directions at
        multiple lag magnitudes and re-estimate.

        Forward shifts (future treatment → current outcome) detect
        autocorrelated trends.  Backward shifts (past treatment → current
        outcome) detect reverse causation / lagged confounding.

        If ANY shift/direction produces an effect whose magnitude rivals
        the real estimate, the causal claim is suspect.
        """
        # Resolve temporal column from spec
        temporal_col = None
        if spec.structural_breaks:
            temporal_col = spec.structural_breaks[0].temporal_column
        if temporal_col is None and spec.residual_checks.autocorrelation:
            temporal_col = spec.residual_checks.autocorrelation[0].temporal_column
        if temporal_col is None:
            dt_cols = data.raw.select_dtypes(include=["datetime", "datetimetz"]).columns
            if len(dt_cols) > 0:
                temporal_col = dt_cols[0]
        if temporal_col is None or temporal_col not in data.columns:
            return {"error": "no temporal column available for temporal placebo"}

        sort_idx = data.raw[temporal_col].sort_values().index
        sorted_enc = data.encoded.loc[sort_idx].reset_index(drop=True)
        n = len(sorted_enc)

        # Probe at 10%, 20%, 33% of the dataset in both directions
        lag_fractions = [0.10, 0.20, 0.33]
        shifts = []
        for frac in lag_fractions:
            lag = max(1, int(n * frac))
            shifts.append((f"forward_{lag}", lag))
            shifts.append((f"backward_{lag}", -lag))

        placebo_dag = self._replace_dag_node(
            dag_nx, spec.treatment, f"_tp_{spec.treatment}"
        )
        placebo_col = f"_tp_{spec.treatment}"

        mem = self._mem_estimate(data)

        def _run_probe(label, lag):
            df = sorted_enc.copy()
            df[placebo_col] = df[spec.treatment].shift(lag)
            df = df.dropna(subset=[placebo_col])
            if len(df) < 5:
                return {"label": label, "lag": lag,
                        "error": f"only {len(df)} rows after shift"}
            # Wrap as PipelineDataFrame (already encoded, no categoricals)
            probe_data = PipelineDataFrame(df, df, [], {})
            effect = self._run_dml_quick(
                probe_data, placebo_col, spec.outcome, placebo_dag
            )
            if effect is None:
                return {"label": label, "lag": lag,
                        "error": "DML estimation failed"}
            ratio = abs(effect) / abs(primary_effect) if primary_effect else 0
            return {
                "label": label, "lag": lag, "n_obs": len(df),
                "effect": float(effect), "ratio": float(ratio),
            }

        probe_futures = [
            self._pool_submit(mem, lambda l=label, g=lag: _run_probe(l, g))
            for label, lag in shifts
        ]
        probes = [f.result() for f in probe_futures]
        worst_ratio = max(
            (p.get("ratio", 0) for p in probes if "error" not in p),
            default=0.0,
        )

        flag = worst_ratio > spec.gates.placebo.flag_ratio
        return {
            "temporal_column": temporal_col,
            "original_effect": float(primary_effect) if primary_effect else None,
            "probes": probes,
            "worst_ratio": float(worst_ratio),
            "flag": flag,
        }

    @staticmethod
    @staticmethod
    def _replace_dag_node(dag: nx.DiGraph, old_node: str, new_node: str) -> nx.DiGraph:
        """Return a new DiGraph with old_node renamed to new_node."""
        mapping = {old_node: new_node}
        return nx.relabel_nodes(dag, mapping)

    # ------------------------------------------------------------------
    # Step 8: Unmeasured confounding
    # ------------------------------------------------------------------

    def _unmeasured_confounding(self, data, spec, estimation_results) -> list[dict]:
        enc = data.encoded
        baseline_rate = float(enc[spec.outcome].mean())
        results = []
        for uc in spec.unmeasured_confounding:
            variant = estimation_results.get(uc.variant_id)
            if variant is None:
                results.append({"variant_id": uc.variant_id, "error": "variant not found in estimation results"})
                continue
            effect = variant.get("effect")
            ci = variant.get("ci")
            if effect is None:
                results.append({"variant_id": uc.variant_id,
                                "error": f"variant estimation failed: {variant.get('error', 'unknown')}"})
                continue

            if uc.method == UnmeasuredMethod.E_VALUE:
                # Determine scaling multiplier for effect → absolute risk change
                v_result = estimation_results.get(uc.variant_id, {})
                is_binary = v_result.get("discrete", False)
                if is_binary:
                    multiplier = 1.0  # binary: effect is already per-unit (0→1)
                else:
                    raw_t = data[spec.treatment]
                    if pd.api.types.is_numeric_dtype(raw_t):
                        iqr = float(raw_t.quantile(0.75) - raw_t.quantile(0.25))
                        std = float(raw_t.std())
                        multiplier = iqr if iqr > 0 else std if std > 0 else 1.0
                    else:
                        multiplier = 1.0
                abs_eff = abs(effect * multiplier)
                rr = (baseline_rate + abs_eff) / baseline_rate if baseline_rate > 0 else 1
                e_point = rr + math.sqrt(rr * (rr - 1)) if rr > 1 else 1.0

                e_ci = 1.0
                if ci:
                    abs_ci = abs(ci[0] * multiplier)
                    rr_ci = max(1.0, (baseline_rate + abs_ci) / baseline_rate) if baseline_rate > 0 else 1
                    e_ci = rr_ci + math.sqrt(rr_ci * (rr_ci - 1)) if rr_ci > 1 else 1.0

                results.append({
                    "variant_id": uc.variant_id,
                    "method": "E_VALUE",
                    "rr": float(rr),
                    "e_value_point": float(e_point),
                    "e_value_ci": float(e_ci),
                    "null_hypothesis": uc.null_hypothesis,
                })
            else:
                results.append({
                    "variant_id": uc.variant_id,
                    "method": "ROSENBAUM_BOUNDS",
                    "notes": uc.notes,
                })
        return results

    # ------------------------------------------------------------------
    # Step 9: Sensitivity
    # ------------------------------------------------------------------

    def _sensitivity(self, data, spec, refined_edges, primary_effect, estimation_results, budget=None) -> dict:
        result: dict[str, Any] = {}

        # Confounder drops — each is an independent DML fit
        mem = self._mem_estimate(data)

        def _run_drop(drop):
            dag_v = self._edges_to_nx(
                [(s, d) for s, d in refined_edges
                 if s != drop.column and d != drop.column]
            )
            est = self._run_dml_quick(data, spec.treatment, spec.outcome, dag_v)
            deviation = (abs(est - primary_effect) / abs(primary_effect) * 100
                         if est is not None and primary_effect else None)
            return {
                "column": drop.column,
                "effect": float(est) if est is not None else None,
                "deviation_pct": float(deviation) if deviation is not None else None,
                "threshold_pct": drop.deviation_threshold_pct,
                "flag": deviation is not None and deviation > drop.deviation_threshold_pct,
            }

        drop_futures = [
            (drop, self._pool_submit(mem, lambda d=drop: _run_drop(d)))
            for drop in spec.sensitivity.confounder_drops
        ]
        result["confounder_drops"] = [f.result() for _, f in drop_futures]

        # Confounder adds — each is an independent DML fit
        def _run_add(add):
            if add.column not in data.columns:
                return {"column": add.column, "error": "column not in data"}
            dag_v = self._edges_to_nx(refined_edges + [(add.column, spec.outcome)])
            est = self._run_dml_quick(data, spec.treatment, spec.outcome, dag_v)
            deviation = (abs(est - primary_effect) / abs(primary_effect) * 100
                         if est is not None and primary_effect else None)
            return {
                "column": add.column,
                "reasoning": add.reasoning,
                "effect": float(est) if est is not None else None,
                "deviation_pct": float(deviation) if deviation is not None else None,
            }

        add_futures = [
            (add, self._pool_submit(mem, lambda a=add: _run_add(a)))
            for add in spec.sensitivity.confounder_adds
        ]
        result["confounder_adds"] = [f.result() for _, f in add_futures]

        # Threshold variants
        thresh = []
        for tv in spec.sensitivity.threshold_variants:
            col = f"_thresh_{tv.threshold}"
            bin_series = (data.encoded[spec.treatment] > tv.threshold).astype(int)
            n_treated = int(bin_series.sum())
            n_control = int((~bin_series.astype(bool)).sum())
            # Build a temporary PipelineDataFrame with the binary column added
            aug_enc = data.encoded.copy()
            aug_enc[col] = bin_series
            aug_raw = data.raw.copy()
            aug_raw[col] = bin_series
            aug_data = PipelineDataFrame(aug_raw, aug_enc, data.cat_columns, data.encoders)
            dag_v = self._edges_to_nx(
                [(col if s == spec.treatment else s,
                  col if d == spec.treatment else d)
                 for s, d in refined_edges]
            )
            est = self._run_dml_quick(aug_data, col, spec.outcome, dag_v)
            thresh.append({
                "threshold": tv.threshold,
                "effect": float(est) if est is not None else None,
                "n_treated": n_treated,
                "n_control": n_control,
                "expected_n_treated": tv.expected_n_treated,
                "expected_n_control": tv.expected_n_control,
            })
        result["threshold_variants"] = thresh

        # Model variants
        mvs = []
        for mv in spec.sensitivity.model_variants:
            primary = estimation_results.get(mv.primary_variant_id, {})
            primary_eff = primary.get("effect")
            mvs.append({
                "primary_variant_id": mv.primary_variant_id,
                "alternative_model_type": mv.alternative_model_type,
                "primary_effect": float(primary_eff) if primary_eff is not None else None,
            })
        result["model_variants"] = mvs

        return result

    # ------------------------------------------------------------------
    # Step 10: Structural breaks
    # ------------------------------------------------------------------

    def _structural_breaks(self, data, spec, effect, ci) -> list[dict]:
        # Mix raw grouping columns with encoded numeric columns for aggregation
        work = pd.DataFrame({
            "_entity": data.raw[spec.structural_breaks[0].entity_column]
            if spec.structural_breaks else pd.Series(dtype="object"),
        })
        results = []
        for sb in spec.structural_breaks:
            if sb.entity_column not in data.columns or sb.temporal_column not in data.columns:
                results.append({"id": sb.id, "error": "column not found"})
                continue

            try:
                df_agg = pd.DataFrame({
                    sb.entity_column: data.raw[sb.entity_column],
                    "_period": pd.to_datetime(data.raw[sb.temporal_column]).dt.to_period(
                        sb.temporal_grain[0].upper()),
                    spec.outcome: data.encoded[spec.outcome],
                    spec.treatment: data.encoded[spec.treatment],
                })
                agg = (
                    df_agg.groupby([sb.entity_column, "_period"])
                    .agg(
                        _rate=(spec.outcome, "mean"),
                        _mean_t=(spec.treatment, "mean"),
                        _n=(spec.outcome, "count"),
                    )
                    .reset_index()
                )
                agg["_ts"] = agg["_period"].dt.to_timestamp()

                matches = []
                for entity in agg[sb.entity_column].unique():
                    ed = agg[agg[sb.entity_column] == entity].sort_values("_period")
                    series = ed["_rate"].values
                    if len(series) < sb.min_obs_per_period:
                        continue
                    try:
                        brks = ruptures.Pelt(model="rbf").fit(series).predict(
                            pen=sb.pelt_penalty
                        )
                    except Exception:
                        continue
                    for bi in brks[:-1]:
                        if bi < 3 or bi > len(series) - 3:
                            continue
                        break_date = str(ed.iloc[bi]["_ts"])
                        pre = float(series[max(0, bi - 6):bi].mean())
                        post = float(series[bi:min(len(series), bi + 6)].mean())
                        actual = post - pre
                        pre_t = float(ed.iloc[max(0, bi - 3):bi]["_mean_t"].mean())
                        post_t = float(ed.iloc[bi:bi + 3]["_mean_t"].mean())
                        t_delta = post_t - pre_t
                        predicted = t_delta * effect if effect else 0
                        direction_match = bool(np.sign(actual) == np.sign(predicted)) if predicted != 0 else False
                        within_ci = False
                        if ci and t_delta != 0:
                            within_ci = bool(ci[0] * t_delta <= actual <= ci[1] * t_delta)

                        matches.append({
                            "entity": str(entity),
                            "break_date": break_date,
                            "actual_change": float(actual),
                            "treatment_delta": float(t_delta),
                            "predicted_change": float(predicted),
                            "direction_match": direction_match,
                            "within_ci": within_ci,
                        })

                ci_matches = [m for m in matches if m["within_ci"]]
                dir_matches = [m for m in matches if m["direction_match"]]
                tier = 1 if len(ci_matches) >= 2 else (2 if len(dir_matches) >= 1 else 3)

                results.append({
                    "id": sb.id,
                    "total_breaks": len(matches),
                    "direction_matches": len(dir_matches),
                    "ci_matches": len(ci_matches),
                    "tier": tier,
                    "matches": matches,
                })
            except Exception as e:
                results.append({"id": sb.id, "error": str(e)})
        return results

    # ------------------------------------------------------------------
    # Step 11: Residual diagnostics
    # ------------------------------------------------------------------

    def _residual_diagnostics(self, data, spec, confounders, refined_edges, effect, budget=None) -> dict:
        result: dict[str, Any] = {}

        # Compute residuals
        X_c = data.encoded_values(confounders)
        Y_v = data.encoded[spec.outcome].values
        T_v = data.encoded[spec.treatment].values
        y_res = np.zeros(len(data))
        t_res = np.zeros(len(data))
        kf = KFold(n_splits=5, shuffle=True, random_state=RANDOM_STATE)
        for tr, te in kf.split(X_c):
            m_y = LGBMRegressor(**LGBM_DEFAULTS).fit(X_c[tr], Y_v[tr])
            m_t = LGBMRegressor(**LGBM_DEFAULTS).fit(X_c[tr], T_v[tr])
            y_res[te] = Y_v[te] - m_y.predict(X_c[te])
            t_res[te] = T_v[te] - m_t.predict(X_c[te])
        final_res = y_res - (effect or 0) * t_res

        # Autocorrelation
        ac_results = []
        for ac in spec.residual_checks.autocorrelation:
            if ac.temporal_column in data.columns:
                sorted_idx = data.sort_values(ac.temporal_column).index
                dw = float(durbin_watson(final_res[sorted_idx]))
                lb = acorr_ljungbox(final_res[sorted_idx], lags=ac.lags, return_df=True)
                flagged = dw < ac.threshold or bool((lb["lb_pvalue"] < CI_ALPHA).any())
                ac_results.append({
                    "temporal_column": ac.temporal_column,
                    "durbin_watson": dw,
                    "flagged": flagged,
                })
        result["autocorrelation"] = ac_results

        # Field correlations
        fc = spec.residual_checks.field_correlation
        correlations = []
        for col in fc.check_columns:
            if col not in data.columns:
                continue
            vals = data.encoded[col].values.astype(float)
            valid = ~np.isnan(vals)
            if valid.sum() < 100:
                continue
            r, _ = pearsonr(final_res[valid], vals[valid])
            if abs(r) > fc.threshold:
                is_meta = any(
                    mc.column == col for mc in spec.residual_checks.metadata_correlation
                )
                correlations.append({
                    "column": col, "correlation": float(r), "is_metadata": is_meta,
                })
        correlations.sort(key=lambda x: (-x.get("is_metadata", False), -abs(x["correlation"])))
        result["field_correlations"] = correlations

        # Auto-correction — skip any column on the treatment's causal pathway:
        # mediators (treatment → col → outcome), descendants of treatment,
        # AND parents/ancestors of treatment (col → treatment) whose variation
        # is part of the treatment mechanism.  Only truly omitted confounders
        # (no directed path through treatment) should be added.
        mediator_cols = {m.column for m in spec.mediators_excluded}
        dag = self._edges_to_nx(refined_edges)
        descendants = nx.descendants(dag, spec.treatment) if dag.has_node(spec.treatment) else set()
        ancestors = nx.ancestors(dag, spec.treatment) if dag.has_node(spec.treatment) else set()
        excluded = mediator_cols | descendants | ancestors | {spec.treatment, spec.outcome}
        logger.info("Auto-correction exclusions: mediators=%s, descendants=%s, "
                    "ancestors=%s, total_excluded=%s",
                    sorted(mediator_cols), sorted(descendants),
                    sorted(ancestors), sorted(excluded))

        corrections = []
        corrected_value = effect
        ac_cfg = spec.residual_checks.auto_correction
        eligible = [c for c in correlations if c["column"] not in excluded]
        logger.info("Auto-correction: %d correlated fields, %d eligible after exclusion: %s",
                    len(correlations),
                    len(eligible),
                    [c["column"] for c in eligible])
        for c in eligible[:ac_cfg.max_iterations]:
            dag_aug = self._edges_to_nx(refined_edges + [(c["column"], spec.outcome)])
            corrected = self._run_dml_quick(data, spec.treatment, spec.outcome, dag_aug)
            if corrected is not None:
                delta_frac = (abs(corrected - corrected_value) / abs(corrected_value)
                              if corrected_value else 0)
                corrections.append({
                    "field": c["column"],
                    "is_metadata": c.get("is_metadata", False),
                    "original": float(corrected_value),
                    "corrected": float(corrected),
                    "delta_pct": float(delta_frac * 100),
                })
                if delta_frac * 100 < ac_cfg.stop_criterion_ci_pct:
                    break
                corrected_value = corrected
        result["corrections"] = corrections
        result["corrected_effect"] = float(corrected_value) if corrections else None

        # Metadata correlations
        meta_results = []
        for mc in spec.residual_checks.metadata_correlation:
            if mc.column not in data.columns:
                continue
            vals = data.encoded[mc.column].values.astype(float)
            valid = ~np.isnan(vals)
            if valid.sum() < 100:
                continue
            r, _ = pearsonr(final_res[valid], vals[valid])
            meta_results.append({
                "column": mc.column,
                "correlation": float(r),
                "threshold": mc.threshold,
                "flagged": abs(r) > mc.threshold,
                "alert_type": mc.alert_type,
            })
        result["metadata_correlations"] = meta_results

        return result

    # ------------------------------------------------------------------
    # Step 12: Range checks
    # ------------------------------------------------------------------

    def _range_checks(self, data, spec, confounders, estimation_results, budget=None) -> dict:
        result: dict[str, Any] = {}

        # VIF (add intercept column — variance_inflation_factor requires it)
        vif_cols = confounders + [spec.treatment]
        X_vif = data.encoded[vif_cols].dropna().astype(float)
        X_vif_const = np.column_stack([np.ones(len(X_vif)), X_vif.values])
        vif_values = {}
        for i, col in enumerate(vif_cols):
            try:
                vif_values[col] = float(
                    variance_inflation_factor(X_vif_const, i + 1)
                )
            except Exception as e:
                logger.warning("VIF computation failed for column '%s': %s", col, e)
                vif_values[col] = None
        result["vif"] = {
            "values": vif_values,
            "threshold": spec.range_checks.vif.threshold,
            "flagged": [col for col, v in vif_values.items()
                        if v is not None and v > spec.range_checks.vif.threshold],
        }

        # Treatment CV
        t_enc = data.encoded[spec.treatment]
        cv = float(t_enc.std() / t_enc.mean()) if t_enc.mean() != 0 else 0
        result["treatment_cv"] = cv

        # Overlap
        overlaps = []
        for ov in spec.range_checks.overlap:
            variant = estimation_results.get(ov.variant_id, {})
            t_col = variant.get("treatment_column", spec.treatment)
            try:
                # Binary threshold columns (_bin_*) are created during estimation
                # but don't exist in the original data — recreate them
                if t_col.startswith("_bin_") and t_col not in data.columns:
                    parts = t_col.split("_", 3)  # _bin_{col}_{threshold}
                    src_col = parts[2] if len(parts) >= 3 else spec.treatment
                    threshold = float(parts[3]) if len(parts) >= 4 else 0
                    logger.info("Overlap: reconstructing binary column '%s' from "
                                "'%s' > %s (parts=%s)", t_col, src_col, threshold, parts)
                    binary = (data.encoded[src_col] > threshold).astype(int)
                    logger.info("Overlap: reconstructed %d treated / %d control",
                                int(binary.sum()), int((~binary.astype(bool)).sum()))
                else:
                    binary = data.encoded[t_col]
                if binary.nunique() > 2:
                    binary = (binary > binary.median()).astype(int)
                if binary.nunique() < 2:
                    overlaps.append({"variant_id": ov.variant_id,
                                     "error": "treatment has < 2 classes after binarization"})
                    continue
                enc_conf = data.encoded[confounders]
                ps = LogisticRegression(max_iter=1000).fit(
                    enc_conf, binary
                ).predict_proba(enc_conf)[:, 1]
                c_lo = max(np.percentile(ps[binary == 1], 5),
                           np.percentile(ps[binary == 0], 5))
                c_hi = min(np.percentile(ps[binary == 1], 95),
                           np.percentile(ps[binary == 0], 95))
                overlap_val = float(np.mean((ps >= c_lo) & (ps <= c_hi)))
                overlaps.append({
                    "variant_id": ov.variant_id,
                    "overlap": overlap_val,
                    "threshold": ov.threshold,
                    "flagged": overlap_val < ov.threshold,
                    "response_strategy": ov.response_strategy.value,
                })
            except Exception as e:
                overlaps.append({"variant_id": ov.variant_id, "error": str(e)})
        result["overlap"] = overlaps

        # Variance
        var_checks = []
        for vc in spec.range_checks.variance:
            if vc.column in data.columns:
                col_enc = data.encoded[vc.column]
                std = float(col_enc.std())
                mean = float(col_enc.mean())
                var_checks.append({
                    "column": vc.column,
                    "std": std,
                    "cv": float(std / mean) if mean != 0 else None,
                    "structural_note": vc.structural_note,
                })
        result["variance"] = var_checks

        return result

    # ------------------------------------------------------------------
    # Null-finding diagnostics (CI crosses zero)
    # ------------------------------------------------------------------

    def _null_diagnostics(self, data, spec, confounders,
                          primary_effect, primary_ci, estimation_results,
                          sensitivity_result, budget=None) -> dict:
        """Extra diagnostics for null findings: absorption curve, power, edge scan."""
        result: dict[str, Any] = {}
        result["absorption_curve"] = self._absorption_curve(
            data, spec, confounders, sensitivity_result)
        result["power_analysis"] = self._power_analysis(
            data, spec, primary_effect, primary_ci)
        result["subpopulation_edges"] = self._subpopulation_edge_scan(
            estimation_results)
        return result

    def _absorption_curve(self, data, spec, confounders, sensitivity_result) -> dict:
        """Cumulative confounding absorption: DML with incrementally added W."""
        drops = sensitivity_result.get("confounder_drops", [])
        drop_map = {d["column"]: abs(d.get("deviation_pct") or 0) for d in drops}
        ordered = sorted(confounders, key=lambda c: drop_map.get(c, 0), reverse=True)

        discrete = spec.estimation_variants[0].treatment_form != TreatmentForm.CONTINUOUS
        enc = data.encoded
        Y = enc[spec.outcome].values
        T = enc[spec.treatment].values
        model_t_cls = LGBMClassifier if discrete else LGBMRegressor
        mem = self._mem_estimate(data)

        def _fit(w_cols: list[str]) -> float | None:
            try:
                W = enc[w_cols].values if w_cols else None
                dml = LinearDML(
                    model_y=LGBMRegressor(**LGBM_DEFAULTS),
                    model_t=model_t_cls(**LGBM_DEFAULTS),
                    discrete_treatment=discrete,
                )
                dml.fit(Y, T, W=W)
                return float(dml.effect().mean())
            except Exception as e:
                logger.warning("Absorption curve fit failed (W=%s): %s", w_cols, e)
                return None

        valid_cols = [c for c in ordered if c in enc.columns]
        futures: list[tuple[str | None, Future]] = [
            (None, self._pool_submit(mem, lambda: _fit([])))
        ]
        for i, col in enumerate(valid_cols):
            w_up_to = list(valid_cols[:i + 1])
            futures.append((col, self._pool_submit(mem, lambda w=w_up_to: _fit(w))))

        curve = []
        w_so_far: list[str] = []
        for step, (col, f) in enumerate(futures):
            eff = f.result()
            prev_eff = curve[-1]["effect"] if curve else None
            absorbed = (prev_eff - eff
                        if eff is not None and prev_eff is not None
                        else None)
            if col is not None:
                w_so_far.append(col)
            curve.append({
                "step": step,
                "added": col,
                "confounders": list(w_so_far),
                "effect": eff,
                "absorbed": absorbed,
            })

        return {"curve": curve, "confounder_order": valid_cols}

    def _power_analysis(self, data, spec, primary_effect, primary_ci) -> dict:
        """MDE at 80% power given observed SE from bootstrap CI."""
        n = len(data)
        ci_lo, ci_hi = primary_ci
        z_alpha = norm.ppf(1 - CI_ALPHA / 2)
        z_beta = norm.ppf(0.80)

        se_obs = (ci_hi - ci_lo) / (2 * z_alpha)
        mde = (z_alpha + z_beta) * se_obs

        n_levels = int(data.raw[spec.treatment].nunique())

        n_needed = None
        below_mde = None
        if primary_effect and primary_effect != 0:
            below_mde = abs(primary_effect) < mde
            if below_mde:
                n_needed = int(math.ceil(n * (mde / abs(primary_effect)) ** 2))

        return {
            "n_observations": n,
            "n_treatment_levels": n_levels,
            "observed_se": float(se_obs),
            "mde_80_power": float(mde),
            "observed_effect": float(primary_effect) if primary_effect else None,
            "effect_below_mde": below_mde,
            "n_needed_for_observed_effect": n_needed,
        }

    @staticmethod
    def _subpopulation_edge_scan(estimation_results, z_threshold: float = 1.0) -> list:
        """Flag variants where CI is within z_threshold SEs of crossing zero."""
        z_alpha = norm.ppf(1 - CI_ALPHA / 2)
        edges = []
        for vid, est in estimation_results.items():
            ci = est.get("ci")
            eff = est.get("effect")
            if ci is None or eff is None or eff == 0:
                continue
            ci_lo, ci_hi = ci
            crosses_zero = ci_lo <= 0 <= ci_hi
            nearest_to_zero = min(abs(ci_lo), abs(ci_hi))
            se = (ci_hi - ci_lo) / (2 * z_alpha)
            z_nearest = nearest_to_zero / se if se > 0 else float("inf")

            if z_nearest < z_threshold:
                edges.append({
                    "variant_id": vid,
                    "effect": eff,
                    "ci": [ci_lo, ci_hi],
                    "crosses_zero": crosses_zero,
                    "nearest_bound_to_zero": float(nearest_to_zero),
                    "z_nearest": float(z_nearest),
                    "se": float(se),
                    "classification": ("barely_insignificant"
                                       if crosses_zero
                                       else "barely_significant"),
                })

        return sorted(edges, key=lambda e: e["z_nearest"])

    # ------------------------------------------------------------------
    # Step 13: Externalization
    # ------------------------------------------------------------------

    def _externalization(self, data, spec, pipeline_result) -> dict:
        result: dict[str, Any] = {}

        # Domain rankings — tier ordering notation evaluated against
        # empirical effects.  Source depends on treatment form:
        #   - Continuous → threshold_variants dose-response effects
        #   - Categorical → GRF category-specific CATEs
        from service.tier_ordering import parse_ordering, evaluate_ordering

        grf_results = pipeline_result.get("steps", {}).get("grf")
        grf_effects: dict[str, float] = {}
        if grf_results:
            for grf_r in grf_results:
                if isinstance(grf_r, dict) and "slices" in grf_r:
                    for key, val in grf_r["slices"].items():
                        grf_effects[key] = val["mean_cate"]

        tv_effects: dict[str, float] = {}
        tv_sample_sizes: dict[str, int] = {}
        for tv in (pipeline_result.get("steps", {})
                .get("sensitivity", {})
                .get("threshold_variants", [])):
            if tv.get("effect") is not None:
                key = str(tv["threshold"])
                tv_effects[key] = tv["effect"]
                tv_sample_sizes[key] = tv.get("n_control", 0)

        rankings = []
        for dr in spec.externalization.domain_rankings:
            entry: dict[str, Any] = {
                "ordering": dr.ordering,
                "source": dr.source,
                "scope": dr.scope,
                "expected_concordance": dr.expected_concordance,
            }
            try:
                ordering = parse_ordering(dr.ordering)
            except ValueError as e:
                entry["error"] = str(e)
                rankings.append(entry)
                continue

            # Pick effect source + sample sizes
            sample_sizes: dict[str, int] = {}
            if tv_effects and spec.treatment_form == TreatmentForm.CONTINUOUS:
                effects = tv_effects
                sample_sizes = tv_sample_sizes
                entry["source_type"] = "threshold_variants"
            else:
                # Categorical: use per-category effects from primary estimation
                primary_est = (pipeline_result.get("steps", {})
                               .get("estimation", {})
                               .get(spec.estimation_variants[0].id, {}))
                cat_effects = primary_est.get("category_effects", {})
                effects = cat_effects if cat_effects else grf_effects
                entry["source_type"] = "category_effects" if cat_effects else "grf_slices"
                # Per-category sample sizes from raw treatment column
                if cat_effects:
                    col = spec.treatment
                    counts = data.raw[col].value_counts()
                    for label in cat_effects:
                        # label is "col=value", extract value part
                        val = label.split("=", 1)[1] if "=" in label else label
                        if val in counts.index:
                            sample_sizes[label] = int(counts[val])
            entry["effects_used"] = effects

            eval_result = evaluate_ordering(ordering, effects, sample_sizes=sample_sizes)
            entry.update(eval_result.to_dict())
            entry["pass"] = (eval_result.concordance >= dr.expected_concordance
                             if eval_result.total_pairs > 0 else None)

            rankings.append(entry)
        result["domain_rankings"] = rankings

        # Allocation bias
        alloc_results = []
        for ab in spec.externalization.allocation_bias:
            if ab.treatment_column in data.columns and ab.grouping_column in data.columns:
                try:
                    ct = pd.crosstab(
                        data[ab.treatment_column], data[ab.grouping_column], normalize="index"
                    )
                    max_dev = float(ct.values.std())
                    alloc_results.append({
                        "treatment_column": ab.treatment_column,
                        "grouping_column": ab.grouping_column,
                        "max_deviation": max_dev,
                        "flagged": max_dev > ab.flag_threshold,
                    })
                except Exception as e:
                    alloc_results.append({
                        "treatment_column": ab.treatment_column,
                        "grouping_column": ab.grouping_column,
                        "error": str(e),
                    })
        result["allocation_bias"] = alloc_results

        return result

    # ------------------------------------------------------------------
    # Utility methods
    # ------------------------------------------------------------------

    @staticmethod
    def _parse_dag_edges(dag_str: str) -> list[tuple[str, str]]:
        edges = []
        for part in dag_str.replace("\n", ";").split(";"):
            part = part.strip()
            if "->" in part:
                src, dst = part.split("->", 1)
                edges.append((src.strip(), dst.strip()))
        return edges

    @staticmethod
    @staticmethod
    def _edges_to_nx(edges: list[tuple[str, str]]) -> nx.DiGraph:
        G = nx.DiGraph()
        G.add_edges_from(edges)
        return G

    @staticmethod
    def _apply_variant_filter(
            data: "PipelineDataFrame", variant: EstimationVariant
    ) -> "PipelineDataFrame":
        if variant.filter is None:
            return data
        mask = CausalVerificationService._eval_filter(data.raw, variant.filter)
        return data.filter_mask(mask)

    @staticmethod
    def _eval_filter(df: pd.DataFrame, f: "VariantFilter") -> "pd.Series[bool]":
        if f.and_filters is not None:
            mask = pd.Series(True, index=df.index)
            for sub in f.and_filters:
                mask = mask & CausalVerificationService._eval_filter(df, sub)
            return mask
        if f.or_filters is not None:
            mask = pd.Series(False, index=df.index)
            for sub in f.or_filters:
                mask = mask | CausalVerificationService._eval_filter(df, sub)
            return mask
        if f.not_filter is not None:
            return ~CausalVerificationService._eval_filter(df, f.not_filter)
        # leaf
        col = f.column
        if col not in df.columns:
            return pd.Series(True, index=df.index)
        if f.operator == FilterOperator.IN:
            return df[col].isin(f.values)
        elif f.operator == FilterOperator.GT:
            return df[col] > f.values[0]
        elif f.operator == FilterOperator.LT:
            return df[col] < f.values[0]
        elif f.operator == FilterOperator.EQ:
            return df[col] == f.values[0]
        return pd.Series(True, index=df.index)

    def _run_dml_quick(self, data, treatment, outcome, dag: nx.DiGraph) -> Optional[float]:
        """Fast DML estimate using econml directly (bypasses DoWhy graph layer)."""
        try:
            # W = all parents of treatment and outcome, minus treatment itself
            w_cols = sorted(
                ((set(dag.predecessors(treatment)) if dag.has_node(treatment) else set())
                 | (set(dag.predecessors(outcome)) if dag.has_node(outcome) else set()))
                - {treatment, outcome}
            )
            w_cols = [c for c in w_cols if c in data.columns]
            if not w_cols:
                w_cols = [c for c in data.columns if c not in (treatment, outcome)]

            enc = data.encoded
            Y = enc[outcome].values
            T = enc[treatment].values
            W = enc[w_cols].values

            dml = LinearDML(
                model_y=LGBMRegressor(**LGBM_DEFAULTS),
                model_t=LGBMRegressor(**LGBM_DEFAULTS),
                discrete_treatment=False,
            )
            dml.fit(Y, T, W=W)
            return float(dml.effect().mean())
        except Exception as e:
            logger.warning(f"Quick DML failed: {e}")
            return None
