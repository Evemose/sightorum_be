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
import pandas as pd
import ruptures
import threading
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
from lightgbm import LGBMRegressor
from scipy.stats import pearsonr
from sklearn.linear_model import LinearRegression, LogisticRegression
from sklearn.model_selection import KFold, cross_val_score
from statsmodels.stats.diagnostic import acorr_ljungbox
from statsmodels.stats.outliers_influence import variance_inflation_factor
from statsmodels.stats.stattools import durbin_watson
from typing import Any, Callable, Optional

warnings.filterwarnings("ignore")
logger = logging.getLogger(__name__)

LGBM_DEFAULTS = dict(n_estimators=300, max_depth=6, learning_rate=0.05, verbose=-1)
BOOTSTRAP_SAMPLES = 20
GRF_ESTIMATORS = 200
GRF_MIN_LEAF = 50
RANDOM_STATE = 42
CI_ALPHA = 0.05


class CausalVerificationService:

    def __init__(self, db_storage=None, worker_pool=None):
        self._db_storage = db_storage
        self._worker_pool = worker_pool

    def _pool_submit(self, mem_bytes: int, fn: Callable[[], Any]) -> "Future[Any]":
        """Submit to pool if available, else resolve immediately."""
        if self._worker_pool:
            return self._worker_pool.submit(mem_bytes, fn)
        f: Future = Future()
        try:
            f.set_result(fn())
        except Exception as e:
            f.set_exception(e)
        return f

    @staticmethod
    def _mem_estimate(data: pd.DataFrame, factor: int = 3) -> int:
        """Rough memory estimate for one DML fit over data."""
        return int(data.memory_usage(deep=True).sum()) * factor

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
        aborted = False

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
        dag_dot = self._edges_to_dot(refined_edges)

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
        # Step 3  —  Estimation variants
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
            primary_variant = spec.estimation_variants[0]
            mem = self._mem_estimate(data)
            variant_futures = {
                v.id: self._pool_submit(
                    mem, lambda v=v: self._run_estimation_variant(
                        data, v, refined_edges, dag_dot, spec.outcome
                    )
                )
                for v in spec.estimation_variants
            }
            for vid, future in variant_futures.items():
                estimation_results[vid] = future.result()

            primary_effect = estimation_results[primary_variant.id]["effect"]
            primary_ci = estimation_results[primary_variant.id].get("ci")
            _cp_save("estimation", {
                "estimation_results": estimation_results,
                "primary_effect": primary_effect,
                "primary_ci": primary_ci,
            })
        result["steps"]["estimation"] = estimation_results

        # ==================================================================
        # Step 4  —  Quality gates
        # ==================================================================
        cp = _cp_load("gates")
        if cp is not None:
            report(0.35, "Quality gates (cached)")
            gates_result = cp["gates_result"]
            aborted = cp["aborted"]
        else:
            report(0.35, "Quality gates")
            gates_result = self._quality_gates(
                data, spec, confounders, primary_effect, primary_ci
            )
            aborted = bool(gates_result.get("abort"))
            _cp_save("gates", {
                "gates_result": gates_result,
                "aborted": aborted,
            })
        result["steps"]["gates"] = gates_result
        if aborted:
            result["aborted"] = True
            result["abort_reason"] = gates_result.get("abort_reason")

        # ==================================================================
        # Steps 5-12  —  Parallel execution (independent post-gate steps)
        #
        # These steps share only read-only inputs (data, confounders,
        # refined_edges, estimation_results, primary_effect/ci).
        # Each step is dispatched on a bare thread; heavy DML/GRF leaf
        # work inside each step goes through _pool_submit so the
        # WorkerPool governs memory and concurrency.
        # ==================================================================
        if not aborted:
            step_outputs: dict[str, Any] = {}
            step_lock = threading.Lock()

            def _run_cached_step(
                    name: str, cp_key: str, cp_extract: Callable,
                    compute: Callable, cp_pack: Callable,
            ) -> None:
                """Run a step with checkpoint load/save, store result."""
                cp = _cp_load(cp_key)
                if cp is not None:
                    report(0, f"{name} (cached)")
                    val = cp_extract(cp)
                else:
                    report(0, name)
                    val = compute()
                    _cp_save(cp_key, cp_pack(val))
                with step_lock:
                    step_outputs[cp_key] = val

            threads: list[threading.Thread] = []

            # Step 5 — Mediation
            if spec.mediation:
                threads.append(threading.Thread(
                    target=_run_cached_step,
                    args=(
                        "Mediation", "mediation",
                        lambda cp: cp["mediation_result"],
                        lambda: self._mediation(
                            data, spec, confounders, estimation_results, refined_edges),
                        lambda v: {"mediation_result": v},
                    ),
                ))

            # Step 6 — GRF heterogeneity
            if spec.grf_configs:
                threads.append(threading.Thread(
                    target=_run_cached_step,
                    args=(
                        "GRF heterogeneity", "grf",
                        lambda cp: cp["grf_result"],
                        lambda: self._grf_heterogeneity(data, spec, confounders),
                        lambda v: {"grf_result": v},
                    ),
                ))

            # Step 7 — Refutations
            if spec.refutations:
                threads.append(threading.Thread(
                    target=_run_cached_step,
                    args=(
                        "Refutations", "refutations",
                        lambda cp: cp["refutations_result"],
                        lambda: self._refutations(data, spec, dag_dot, primary_effect),
                        lambda v: {"refutations_result": v},
                    ),
                ))

            # Step 8 — Unmeasured confounding
            if spec.unmeasured_confounding:
                threads.append(threading.Thread(
                    target=_run_cached_step,
                    args=(
                        "Unmeasured confounding", "unmeasured_confounding",
                        lambda cp: cp["uc_result"],
                        lambda: self._unmeasured_confounding(
                            data, spec, estimation_results),
                        lambda v: {"uc_result": v},
                    ),
                ))

            # Step 9 — Sensitivity
            threads.append(threading.Thread(
                target=_run_cached_step,
                args=(
                    "Sensitivity", "sensitivity",
                    lambda cp: cp["sensitivity_result"],
                    lambda: self._sensitivity(
                        data, spec, refined_edges, primary_effect, estimation_results),
                    lambda v: {"sensitivity_result": v},
                ),
            ))

            # Step 10 — Structural breaks
            if spec.structural_breaks:
                threads.append(threading.Thread(
                    target=_run_cached_step,
                    args=(
                        "Structural breaks", "structural_breaks",
                        lambda cp: cp["breaks_result"],
                        lambda: self._structural_breaks(
                            data, spec, primary_effect, primary_ci),
                        lambda v: {"breaks_result": v},
                    ),
                ))

            # Step 11 — Residual diagnostics (returns dict with corrected_effect key)
            threads.append(threading.Thread(
                target=_run_cached_step,
                args=(
                    "Residual diagnostics", "residual_diagnostics",
                    lambda cp: cp["residual_result"],
                    lambda: self._residual_diagnostics(
                        data, spec, confounders, refined_edges, primary_effect),
                    lambda v: {
                        "residual_result": v,
                        "corrected_effect": v.get("corrected_effect"),
                    },
                ),
            ))

            # Step 12 — Range checks
            threads.append(threading.Thread(
                target=_run_cached_step,
                args=(
                    "Range checks", "range_checks",
                    lambda cp: cp["range_result"],
                    lambda: self._range_checks(
                        data, spec, confounders, estimation_results),
                    lambda v: {"range_result": v},
                ),
            ))

            # Launch all, wait for all
            for t in threads:
                t.daemon = True
                t.start()
            for t in threads:
                t.join()

            # Collect results
            result["steps"]["mediation"] = step_outputs.get("mediation")
            result["steps"]["grf"] = step_outputs.get("grf")
            if "refutations" in step_outputs:
                result["steps"]["refutations"] = step_outputs["refutations"]
            if "unmeasured_confounding" in step_outputs:
                result["steps"]["unmeasured_confounding"] = step_outputs["unmeasured_confounding"]
            result["steps"]["sensitivity"] = step_outputs.get("sensitivity")
            if "structural_breaks" in step_outputs:
                result["steps"]["structural_breaks"] = step_outputs["structural_breaks"]
            if "range_checks" in step_outputs:
                result["steps"]["range_checks"] = step_outputs["range_checks"]

            # Residual diagnostics: extract corrected_effect
            rd = step_outputs.get("residual_diagnostics")
            if rd is not None:
                result["steps"]["residual_diagnostics"] = rd
                corrected = rd.get("corrected_effect") if isinstance(rd, dict) else None
                if corrected is not None:
                    primary_effect = corrected

        # ==================================================================
        # Step 13  —  Externalization (depends on GRF from step 6)
        # ==================================================================
        if spec.externalization and not aborted:
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

    def _load_data(self, spec: CausalVerificationRequest, datasource) -> pd.DataFrame:
        sql = spec.datasource.sql
        bind_vars = spec.datasource.bind_variables
        result = datasource.fetch(sql, bind_vars)
        data = result.dataframe.to_pandas()
        if spec.strip_columns:
            data = data.drop(
                columns=[c for c in spec.strip_columns if c in data.columns],
                errors="ignore",
            )
        data = self._encode_categoricals(data)
        return data

    @staticmethod
    def _encode_categoricals(df: pd.DataFrame) -> pd.DataFrame:
        """Label-encode object/category columns to integers for DML compatibility.

        Label encoding (not one-hot) preserves column names so the DAG graph
        stays valid.  LGBMRegressor nuisance models handle ordinal-encoded
        categoricals natively; the DML final-stage LinearRegression only sees
        residualized Y/T, not W directly, so arbitrary ordering is harmless.
        """
        cat_cols = df.select_dtypes(include=["object", "category", "string"]).columns
        if cat_cols.empty:
            return df
        df = df.copy()
        for col in cat_cols:
            df[col] = df[col].astype("category").cat.codes
        return df

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
            if col in cols and not pd.api.types.is_numeric_dtype(data[col]):
                errors.append(f"{context}: column '{col}' has dtype "
                              f"'{data[col].dtype}', expected numeric")

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
                _require_col(v.filter.column, f"estimation_variant '{v.id}' filter")
                if v.filter.operator in (FilterOperator.GT, FilterOperator.LT, FilterOperator.EQ):
                    if not v.filter.values:
                        errors.append(f"estimation_variant '{v.id}' filter: "
                                      f"operator {v.filter.operator.value} requires "
                                      f"at least one value in 'values'")

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
                _require_numeric(c, f"grf_config '{cfg.id}' modifier_columns "
                                    f"(CausalForestDML requires numeric X)")
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
            for dr in spec.externalization.domain_rankings:
                if not (0 <= dr.expected_concordance <= 1):
                    errors.append(f"externalization.domain_rankings '{dr.source}': "
                                  f"expected_concordance must be in [0,1], "
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
        a_vals = data[a].values.astype(float)
        b_vals = data[b].values.astype(float)
        if not cond_set:
            r, p = pearsonr(a_vals, b_vals)
        else:
            X = data[list(cond_set)].values.astype(float)
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
            dag_dot: str,
            outcome_col: str,
    ) -> dict[str, Any]:
        df = self._apply_variant_filter(data, variant)
        treatment_col = variant.treatment_column
        W_cols = variant.w_columns
        discrete = variant.treatment_form != TreatmentForm.CONTINUOUS

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
            df, variant, W_cols, treatment_col, discrete, refined_edges, outcome_col
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
    ) -> dict[str, Any]:
        """Run DML estimation via DoWhy for a single variant."""
        dag_dot = self._edges_to_dot(refined_edges)

        dml_params = {
            "init_params": {
                "model_y": LGBMRegressor(**LGBM_DEFAULTS),
                "model_t": LGBMRegressor(**LGBM_DEFAULTS),
                "model_final": LinearRegression(),
                "discrete_treatment": discrete,
            },
            "fit_params": {
                "inference": BootstrapInference(
                    n_bootstrap_samples=BOOTSTRAP_SAMPLES, n_jobs=-1
                ),
            },
        }

        try:
            model = dowhy.CausalModel(
                data=df, treatment=treatment_col,
                outcome=outcome_col, graph=dag_dot,
            )
            identified = model.identify_effect(proceed_when_unidentifiable=False)
            estimate = model.estimate_effect(
                identified, method_name="backdoor.econml.dml.DML",
                method_params=dml_params,
            )
            effect = float(estimate.value)
            econml_obj = estimate.params["_estimator_object"]
            ci_raw = econml_obj.effect_interval(alpha=CI_ALPHA)
            ci = (float(ci_raw[0].flatten()[0]), float(ci_raw[1].flatten()[0]))
        except Exception as e:
            logger.warning(f"Variant {variant.id} estimation failed: {e}")
            return {"variant_id": variant.id, "effect": None, "ci": None, "error": str(e)}

        return {
            "variant_id": variant.id,
            "effect": effect,
            "ci": ci,
            "treatment_column": treatment_col,
            "w_columns": w_cols,
            "n_obs": len(df),
            "discrete": discrete,
        }

    # ------------------------------------------------------------------
    # Step 4: Quality gates
    # ------------------------------------------------------------------

    def _quality_gates(
            self, data, spec, confounders, effect, ci
    ) -> dict[str, Any]:
        gates = spec.gates
        result: dict[str, Any] = {"abort": False}

        # Nuisance R2
        outcome_r2 = float(np.mean(cross_val_score(
            LGBMRegressor(**LGBM_DEFAULTS), data[confounders], data[spec.outcome],
            cv=5, scoring="r2")))
        treatment_r2 = float(np.mean(cross_val_score(
            LGBMRegressor(**LGBM_DEFAULTS), data[confounders], data[spec.treatment],
            cv=5, scoring="r2")))

        result["nuisance_r2"] = {
            "outcome_r2": outcome_r2,
            "treatment_r2": treatment_r2,
            "outcome_status": "abort" if outcome_r2 < gates.nuisance_r2.outcome_abort
            else "flag" if outcome_r2 < gates.nuisance_r2.outcome_flag
            else "pass",
            "treatment_status": "flag" if treatment_r2 < gates.nuisance_r2.treatment_flag
            else "structural" if treatment_r2 > gates.nuisance_r2.treatment_structural_max_r2
            else "pass",
        }

        if outcome_r2 < gates.nuisance_r2.outcome_abort:
            result["abort"] = True
            result[
                "abort_reason"] = f"Outcome R2 {outcome_r2:.3f} below abort threshold {gates.nuisance_r2.outcome_abort}"
            return result

        # Sanity
        if effect is not None:
            direction_ok = np.sign(effect) == gates.sanity.expected_direction
            magnitude = abs(effect)
            result["sanity"] = {
                "direction_ok": direction_ok,
                "effect_magnitude": magnitude,
                "abort_magnitude": gates.sanity.abort_magnitude,
                "flag_magnitude": gates.sanity.flag_magnitude,
                "status": "abort" if magnitude > gates.sanity.abort_magnitude
                else "flag" if magnitude > gates.sanity.flag_magnitude
                else "pass",
            }
            if magnitude > gates.sanity.abort_magnitude:
                result["abort"] = True
                result["abort_reason"] = f"Effect magnitude {magnitude:.4f} exceeds abort threshold"
                return result
            if not direction_ok:
                result["sanity"]["warning"] = "Effect direction does not match expected"

        return result

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

            W_med = data[confounders + [med.mediator]].values
            try:
                dml = LinearDML(
                    model_y=LGBMRegressor(**LGBM_DEFAULTS),
                    model_t=LGBMRegressor(**LGBM_DEFAULTS),
                    discrete_treatment=False,
                )
                dml.fit(data[spec.outcome].values, data[spec.treatment].values, W=W_med)
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

    def _grf_heterogeneity(self, data, spec, confounders) -> list[dict]:
        def _fit_one_grf(cfg: GrfConfig) -> dict:
            try:
                X_grf = data[cfg.modifier_columns].values
                Y = data[spec.outcome].values
                T = data[spec.treatment].values
                W = data[confounders].values

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

        mem = self._mem_estimate(data, factor=5)
        futures = [
            self._pool_submit(mem, lambda c=cfg: _fit_one_grf(c))
            for cfg in spec.grf_configs
        ]
        return [f.result() for f in futures]

    # ------------------------------------------------------------------
    # Step 7: Refutations
    # ------------------------------------------------------------------

    def _refutations(self, data, spec, dag_dot, primary_effect) -> dict[str, Any]:
        refute_params = {
            "init_params": {
                "model_y": LGBMRegressor(**LGBM_DEFAULTS),
                "model_t": LGBMRegressor(**LGBM_DEFAULTS),
                "model_final": LinearRegression(),
                "discrete_treatment": False,
            },
            "fit_params": {},
        }

        model = dowhy.CausalModel(
            data=data, treatment=spec.treatment,
            outcome=spec.outcome, graph=dag_dot,
        )
        identified = model.identify_effect(proceed_when_unidentifiable=False)
        est = model.estimate_effect(
            identified, method_name="backdoor.econml.dml.DML",
            method_params=refute_params,
        )

        results = {}
        for ref_cfg in spec.refutations:
            try:
                if ref_cfg.type == RefutationType.PLACEBO:
                    r = model.refute_estimate(
                        identified, est,
                        method_name="placebo_treatment_refuter",
                        placebo_type="permute",
                    )
                    ratio = abs(r.new_effect) / abs(primary_effect) if primary_effect else 0
                    results["placebo"] = {
                        "new_effect": float(r.new_effect),
                        "ratio": float(ratio),
                        "flag": ratio > spec.gates.placebo.flag_ratio,
                    }
                elif ref_cfg.type == RefutationType.RANDOM_CAUSE:
                    r = model.refute_estimate(
                        identified, est, method_name="random_common_cause",
                    )
                    shift = (abs(r.new_effect - primary_effect) / abs(primary_effect)
                             if primary_effect else 0)
                    results["random_cause"] = {
                        "new_effect": float(r.new_effect),
                        "shift_pct": float(shift),
                    }
                elif ref_cfg.type == RefutationType.SUBSET:
                    r = model.refute_estimate(
                        identified, est,
                        method_name="data_subset_refuter", subset_fraction=0.8,
                    )
                    shift = (abs(r.new_effect - primary_effect) / abs(primary_effect)
                             if primary_effect else 0)
                    results["subset"] = {
                        "new_effect": float(r.new_effect),
                        "shift_pct": float(shift),
                    }
                elif ref_cfg.type == RefutationType.TEMPORAL_PLACEBO:
                    results["temporal_placebo"] = self._temporal_placebo(
                        data, spec, dag_dot, primary_effect
                    )
            except Exception as e:
                results[ref_cfg.type.value.lower()] = {"error": str(e)}

        return results

    def _temporal_placebo(
            self, data: pd.DataFrame, spec: CausalVerificationRequest,
            dag_dot: str, primary_effect: float,
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
            dt_cols = data.select_dtypes(include=["datetime", "datetimetz"]).columns
            if len(dt_cols) > 0:
                temporal_col = dt_cols[0]
        if temporal_col is None or temporal_col not in data.columns:
            return {"error": "no temporal column available for temporal placebo"}

        sorted_data = data.sort_values(temporal_col).reset_index(drop=True)
        n = len(sorted_data)

        # Probe at 10%, 20%, 33% of the dataset in both directions
        lag_fractions = [0.10, 0.20, 0.33]
        shifts = []
        for frac in lag_fractions:
            lag = max(1, int(n * frac))
            shifts.append((f"forward_{lag}", lag))
            shifts.append((f"backward_{lag}", -lag))

        placebo_dag = self._replace_dag_node(
            dag_dot, spec.treatment, f"_tp_{spec.treatment}"
        )
        placebo_col = f"_tp_{spec.treatment}"

        mem = self._mem_estimate(sorted_data)

        def _run_probe(label, lag):
            df = sorted_data.copy()
            df[placebo_col] = df[spec.treatment].shift(lag)
            df = df.dropna(subset=[placebo_col])
            if len(df) < 5:
                return {"label": label, "lag": lag,
                        "error": f"only {len(df)} rows after shift"}
            effect = self._run_dml_quick(
                df, placebo_col, spec.outcome, placebo_dag
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
    def _replace_dag_node(dag_dot: str, old_node: str, new_node: str) -> str:
        """Replace a node name in a DOT digraph string."""
        lines = []
        for line in dag_dot.split("\n"):
            stripped = line.strip().rstrip(";")
            if "->" in stripped:
                src, dst = [s.strip() for s in stripped.split("->", 1)]
                if src == old_node:
                    src = new_node
                if dst == old_node:
                    dst = new_node
                lines.append(f"    {src} -> {dst};")
            else:
                lines.append(line)
        return "\n".join(lines)

    # ------------------------------------------------------------------
    # Step 8: Unmeasured confounding
    # ------------------------------------------------------------------

    def _unmeasured_confounding(self, data, spec, estimation_results) -> list[dict]:
        baseline_rate = float(data[spec.outcome].mean())
        results = []
        for uc in spec.unmeasured_confounding:
            variant = estimation_results.get(uc.variant_id, {})
            effect = variant.get("effect")
            ci = variant.get("ci")
            if effect is None:
                results.append({"variant_id": uc.variant_id, "error": "variant not found"})
                continue

            if uc.method == UnmeasuredMethod.E_VALUE:
                iqr = float(data[spec.treatment].quantile(0.75)
                            - data[spec.treatment].quantile(0.25))
                multiplier = iqr
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

    def _sensitivity(self, data, spec, refined_edges, primary_effect, estimation_results) -> dict:
        result: dict[str, Any] = {}

        # Confounder drops — each is an independent DML fit
        mem = self._mem_estimate(data)

        def _run_drop(drop):
            dag_v = self._edges_to_dot(
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
            dag_v = self._edges_to_dot(refined_edges + [(add.column, spec.outcome)])
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
            data[col] = (data[spec.treatment] > tv.threshold).astype(int)
            n_treated = int(data[col].sum())
            n_control = int((~data[col].astype(bool)).sum())
            dag_v = self._edges_to_dot(
                [(col if s == spec.treatment else s,
                  col if d == spec.treatment else d)
                 for s, d in refined_edges]
            )
            est = self._run_dml_quick(data, col, spec.outcome, dag_v)
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
        results = []
        for sb in spec.structural_breaks:
            if sb.entity_column not in data.columns or sb.temporal_column not in data.columns:
                results.append({"id": sb.id, "error": "column not found"})
                continue

            try:
                data["_period"] = pd.to_datetime(data[sb.temporal_column]).dt.to_period(
                    sb.temporal_grain[0].upper()
                )
                agg = (
                    data.groupby([sb.entity_column, "_period"])
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

    def _residual_diagnostics(self, data, spec, confounders, refined_edges, effect) -> dict:
        result: dict[str, Any] = {}

        # Compute residuals
        X_c = data[confounders].values
        Y_v = data[spec.outcome].values
        T_v = data[spec.treatment].values
        y_res = np.zeros(len(data))
        t_res = np.zeros(len(data))
        kf = KFold(n_splits=5, shuffle=True, random_state=RANDOM_STATE)
        for tr, te in kf.split(data):
            m_y = LGBMRegressor(**LGBM_DEFAULTS).fit(X_c[tr], Y_v[tr])
            m_t = LGBMRegressor(**LGBM_DEFAULTS).fit(X_c[tr], T_v[tr])
            y_res[te] = Y_v[te] - m_y.predict(X_c[te])
            t_res[te] = T_v[te] - m_t.predict(X_c[te])
        final_res = y_res - effect * t_res

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
            vals = data[col].values
            try:
                vals = vals.astype(float)
            except (ValueError, TypeError):
                continue
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

        # Auto-correction
        corrections = []
        corrected_value = effect
        ac_cfg = spec.residual_checks.auto_correction
        for c in correlations[:ac_cfg.max_iterations]:
            dag_aug = self._edges_to_dot(refined_edges + [(c["column"], spec.outcome)])
            corrected = self._run_dml_quick(data, spec.treatment, spec.outcome, dag_aug)
            if corrected is not None:
                delta_frac = (abs(corrected - corrected_value) / abs(corrected_value)
                              if corrected_value != 0 else 0)
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
            vals = data[mc.column].values
            try:
                vals = vals.astype(float)
            except (ValueError, TypeError):
                continue
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

    def _range_checks(self, data, spec, confounders, estimation_results) -> dict:
        result: dict[str, Any] = {}

        # VIF (add intercept column — variance_inflation_factor requires it)
        vif_cols = confounders + [spec.treatment]
        X_vif = data[vif_cols].dropna()
        X_vif_const = np.column_stack([np.ones(len(X_vif)), X_vif.values])
        vif_values = {}
        for i, col in enumerate(vif_cols):
            try:
                vif_values[col] = float(
                    variance_inflation_factor(X_vif_const, i + 1)
                )
            except Exception:
                vif_values[col] = None
        result["vif"] = {
            "values": vif_values,
            "threshold": spec.range_checks.vif.threshold,
            "flagged": [col for col, v in vif_values.items()
                        if v is not None and v > spec.range_checks.vif.threshold],
        }

        # Treatment CV
        cv = float(data[spec.treatment].std() / data[spec.treatment].mean()) \
            if data[spec.treatment].mean() != 0 else 0
        result["treatment_cv"] = cv

        # Overlap
        overlaps = []
        for ov in spec.range_checks.overlap:
            variant = estimation_results.get(ov.variant_id, {})
            t_col = variant.get("treatment_column", spec.treatment)
            if t_col not in data.columns:
                overlaps.append({"variant_id": ov.variant_id, "error": "column not found"})
                continue
            try:
                binary = data[t_col]
                if binary.nunique() > 2:
                    binary = (binary > binary.median()).astype(int)
                if binary.nunique() < 2:
                    overlaps.append({"variant_id": ov.variant_id,
                                     "error": "treatment has < 2 classes after binarization"})
                    continue
                ps = LogisticRegression(max_iter=1000).fit(
                    data[confounders], binary
                ).predict_proba(data[confounders])[:, 1]
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
                std = float(data[vc.column].std())
                mean = float(data[vc.column].mean())
                var_checks.append({
                    "column": vc.column,
                    "std": std,
                    "cv": float(std / mean) if mean != 0 else None,
                    "structural_note": vc.structural_note,
                })
        result["variance"] = var_checks

        return result

    # ------------------------------------------------------------------
    # Step 13: Externalization
    # ------------------------------------------------------------------

    def _externalization(self, data, spec, pipeline_result) -> dict:
        result: dict[str, Any] = {}

        # Domain rankings
        grf_results = pipeline_result.get("steps", {}).get("grf")
        if grf_results:
            discovered_slopes = {}
            for grf_r in grf_results:
                if isinstance(grf_r, dict) and "slices" in grf_r:
                    for key, val in grf_r["slices"].items():
                        discovered_slopes[key] = val["mean_cate"]

            rankings = []
            for dr in spec.externalization.domain_rankings:
                rankings.append({
                    "source": dr.source,
                    "domain_ranking": dr.domain_ranking,
                    "comparison_method": dr.comparison_method,
                    "scope": dr.scope,
                    "expected_concordance": dr.expected_concordance,
                    "discovered_slopes": discovered_slopes,
                })
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
    def _edges_to_dot(edges: list[tuple[str, str]]) -> str:
        lines = ["digraph {"]
        for src, dst in edges:
            lines.append(f"    {src} -> {dst};")
        lines.append("}")
        return "\n".join(lines)

    @staticmethod
    def _apply_variant_filter(data: pd.DataFrame, variant: EstimationVariant) -> pd.DataFrame:
        if variant.filter is None:
            return data
        f = variant.filter
        col = f.column
        if col not in data.columns:
            return data
        if f.operator == FilterOperator.IN:
            return data[data[col].isin(f.values)]
        elif f.operator == FilterOperator.GT:
            return data[data[col] > f.values[0]]
        elif f.operator == FilterOperator.LT:
            return data[data[col] < f.values[0]]
        elif f.operator == FilterOperator.EQ:
            return data[data[col] == f.values[0]]
        return data

    def _run_dml_quick(self, data, treatment, outcome, dag_str) -> Optional[float]:
        try:
            m = dowhy.CausalModel(
                data=data, treatment=treatment, outcome=outcome, graph=dag_str,
            )
            ident = m.identify_effect(proceed_when_unidentifiable=False)
            est = m.estimate_effect(
                ident, method_name="backdoor.econml.dml.DML",
                method_params={
                    "init_params": {
                        "model_y": LGBMRegressor(**LGBM_DEFAULTS),
                        "model_t": LGBMRegressor(**LGBM_DEFAULTS),
                        "model_final": LinearRegression(),
                        "discrete_treatment": False,
                    },
                    "fit_params": {},
                },
            )
            return float(est.value)
        except Exception as e:
            logger.warning(f"Quick DML failed: {e}")
            return None
