import logging
import networkx as nx
import warnings
from concurrent.futures import Future
from dataclasses import replace as dc_replace
from typing import Any, Callable, Optional

from . import (
    dsep,
    positivity,
    estimation,
    quality_gates as qg_mod,
    mediation as med_mod,
    grf as grf_mod,
    refutations as ref_mod,
    sensitivity as sens_mod,
    residual_diagnostics as rd_mod,
    range_checks as rc_mod,
    unmeasured_confounding as uc_mod,
    null_diagnostics as nd_mod,
    externalization as ext_mod,
    structural_breaks as sb_mod,
    data_loading,
)
from .memory_budget import MemoryBudget, reclaim
from .pipeline_utils import (
    build_protected_columns, edges_to_nx, parse_dag_edges, trace,
)

warnings.filterwarnings("ignore")
logger = logging.getLogger(__name__)


class CausalVerificationService:

    def __init__(self, db_storage=None, worker_pool=None):
        self._db_storage = db_storage
        self._worker_pool = worker_pool

    def _pool_submit(self, budget: MemoryBudget, fn: Callable[[], Any]) -> Future:
        budget.check_and_reclaim()
        f: Future = Future()
        try:
            f.set_result(fn())
        except Exception as e:
            f.set_exception(e)
        reclaim()
        return f

    # -- Backward-compat delegations for callers that access these directly --
    @staticmethod
    def _load_data(spec, datasource):
        return data_loading.load_data(spec, datasource)

    @staticmethod
    def _validate_spec(spec, data):
        return data_loading.validate_spec(spec, data)

    @staticmethod
    def _parse_dag_edges(dag_str):
        return parse_dag_edges(dag_str)

    @staticmethod
    def _edges_to_nx(edges):
        return edges_to_nx(edges)

    def run_pipeline(
            self,
            spec,
            datasource,
            progress_callback: Optional[Callable] = None,
            checkpoint=None,
    ) -> dict[str, Any]:
        from datetime import datetime
        from service.pipeline_checkpoint import PipelineCheckpoint

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

        def _cp_load(step: str):
            return checkpoint.load(step) if checkpoint else None

        def _cp_save(step: str, data: dict):
            if checkpoint:
                checkpoint.save(step, data)

        # Store original_treatment at pipeline start
        if not spec.original_treatment:
            spec = dc_replace(spec, original_treatment=spec.treatment)

        # Step 0 — Load data
        report(0.02, "Loading data")
        data = data_loading.load_data(spec, datasource)
        result["row_count"] = len(data)
        logger.info("Pipeline %s: loaded %d rows × %d cols",
                    spec.hypothesis_id, len(data), data.encoded.shape[1])
        if spec.expected_row_count and len(data) != spec.expected_row_count:
            result["row_count_mismatch"] = {
                "expected": spec.expected_row_count,
                "actual": len(data),
            }

        data_loading.validate_spec(spec, data)
        budget = MemoryBudget(data)

        # Step 1 �� D-sep refinement
        cp = _cp_load("dsep")
        if cp is not None:
            report(0.05, "D-sep refinement (cached)")
            dsep_result = cp["dsep_result"]
            refined_edges = [tuple(e) for e in cp["refined_edges"]]
        else:
            report(0.05, "D-separation refinement")
            edges = parse_dag_edges(spec.dag_edges)
            dsep_result = dsep.dsep_refinement(data, edges, spec.dsep_threshold)
            refined_edges = dsep_result["refined_edges"]
            _cp_save("dsep", {
                "dsep_result": dsep_result,
                "refined_edges": refined_edges,
            })
        result["steps"]["dsep"] = dsep_result
        dag_nx = edges_to_nx(refined_edges)

        # Step 2 — Identification
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

        # Build protected_columns (FIX E3)
        protected_columns = build_protected_columns(spec, dag_nx)

        # Step 2b — Positivity gate
        if spec.positivity_check is not None:
            cp = _cp_load("positivity")
            if cp is not None:
                report(0.12, "Positivity gate (cached)")
                positivity_report = cp["positivity_report"]
                if cp.get("surviving_mask") is not None:
                    data = positivity.apply_positivity_trim(data, cp["surviving_mask"])
                if cp.get("rewritten_variants") is not None:
                    old_treatment = spec.treatment
                    spec = positivity.apply_positivity_rewrite(
                        spec, cp["rewritten_variants"],
                        cp["final_treatment"], cp["surviving_mask"], data)
                    if cp["final_treatment"] and cp["final_treatment"] != old_treatment:
                        refined_edges = [
                            (cp["final_treatment"] if s == old_treatment else s,
                             cp["final_treatment"] if d == old_treatment else d)
                            for s, d in refined_edges
                        ]
                        dag_nx = edges_to_nx(refined_edges)
                        # FIX E13: post-substitution cycle check
                        if not nx.is_directed_acyclic_graph(dag_nx):
                            raise ValueError(
                                f"DAG cycle detected after positivity rewrite: "
                                f"{old_treatment} → {cp['final_treatment']}")
            else:
                report(0.12, "Positivity gate")
                positivity_report, rewritten_variants, final_treatment, surviving_mask = \
                    positivity.positivity_gate(data, spec, budget)
                from dataclasses import asdict
                _cp_save("positivity", {
                    "positivity_report": positivity_report,
                    "rewritten_variants": [asdict(v) for v in rewritten_variants]
                    if rewritten_variants is not None else None,
                    "final_treatment": final_treatment,
                    "surviving_mask": surviving_mask,
                })
                if surviving_mask is not None:
                    data = positivity.apply_positivity_trim(data, surviving_mask)

                if rewritten_variants is not None:
                    old_treatment = spec.treatment
                    spec = positivity.apply_positivity_rewrite(
                        spec, rewritten_variants,
                        final_treatment, surviving_mask, data)

                    refined_edges = [
                        (final_treatment if s == old_treatment else s,
                         final_treatment if d == old_treatment else d)
                        for s, d in refined_edges
                    ]
                    dag_nx = edges_to_nx(refined_edges)
                    if not nx.is_directed_acyclic_graph(dag_nx):
                        raise ValueError(
                            f"DAG cycle detected after positivity rewrite: "
                            f"{old_treatment} → {final_treatment}")
                    trace("DAG updated: %s → %s", old_treatment, final_treatment)
            result["steps"]["positivity"] = positivity_report
            result["row_count"] = len(data)
            # Rebuild protected_columns after positivity rewrite
            protected_columns = build_protected_columns(spec, dag_nx)

        # Step 3 — Estimation variants
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
                        budget, lambda v=v: estimation.run_estimation_variant(
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

        # Step 4 — Quality gates
        cp = _cp_load("gates")
        if cp is not None:
            report(0.35, "Quality gates (cached)")
            gates_result = cp["gates_result"]
        else:
            report(0.35, "Quality gates")
            gates_result = qg_mod.quality_gates(
                data, spec, confounders, primary_effect, primary_ci
            )
            _cp_save("gates", {"gates_result": gates_result})
        result["steps"]["gates"] = gates_result

        step_outputs: dict[str, Any] = {}

        def _run_cached_step(name, cp_key, cp_extract, compute, cp_pack):
            cp = _cp_load(cp_key)
            if cp is not None:
                report(0, f"{name} (cached)")
                return cp_key, cp_extract(cp)
            report(0, name)
            val = compute()
            _cp_save(cp_key, cp_pack(val))
            return cp_key, val

        # Step 5 — Mediation
        if spec.mediation:
            step_outputs["mediation"] = _run_cached_step(
                "Mediation", "mediation",
                lambda cp: cp["mediation_result"],
                lambda: med_mod.mediation(
                    data, spec, confounders, estimation_results, refined_edges),
                lambda v: {"mediation_result": v})[1]
            result["steps"]["mediation"] = step_outputs.get("mediation")
            reclaim()

        # Light steps
        light_pending: dict[str, Future] = {}

        if spec.unmeasured_confounding:
            light_pending["unmeasured_confounding"] = self._pool_submit(
                budget, lambda: _run_cached_step(
                    "Unmeasured confounding", "unmeasured_confounding",
                    lambda cp: cp["uc_result"],
                    lambda: uc_mod.unmeasured_confounding(data, spec, estimation_results),
                    lambda v: {"uc_result": v}))

        if spec.structural_breaks:
            light_pending["structural_breaks"] = self._pool_submit(
                budget, lambda: _run_cached_step(
                    "Structural breaks", "structural_breaks",
                    lambda cp: cp["breaks_result"],
                    lambda: sb_mod.structural_breaks(data, spec, primary_effect, primary_ci),
                    lambda v: {"breaks_result": v}))

        light_pending["range_checks"] = self._pool_submit(
            budget, lambda: _run_cached_step(
                "Range checks", "range_checks",
                lambda cp: cp["range_result"],
                lambda: rc_mod.range_checks(data, spec, confounders, estimation_results, budget),
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
        reclaim()

        # Sensitivity (heavy)
        _, sensitivity_val = _run_cached_step(
            "Sensitivity", "sensitivity",
            lambda cp: cp["sensitivity_result"],
            lambda: sens_mod.sensitivity(
                data, spec, refined_edges, primary_effect, estimation_results,
                budget, pool_submit=self._pool_submit,
                protected_columns=protected_columns),
            lambda v: {"sensitivity_result": v})
        step_outputs["sensitivity"] = sensitivity_val
        result["steps"]["sensitivity"] = sensitivity_val
        reclaim()

        # Refutations (heavy)
        if spec.refutations:
            cp = _cp_load("refutations")
            if cp is not None:
                step_outputs["refutations"] = cp["refutations_result"]
            else:
                report(0, "Refutations")
                refute_futures = ref_mod.refutations_parallel(
                    data, spec, dag_nx, primary_effect, budget, checkpoint,
                    pool_submit=self._pool_submit)
                refute_result = ref_mod.collect_refutations(
                    refute_futures, spec, primary_effect, checkpoint)
                _cp_save("refutations", {"refutations_result": refute_result})
                step_outputs["refutations"] = refute_result
            if "refutations" in step_outputs:
                result["steps"]["refutations"] = step_outputs["refutations"]
            reclaim()

        # Residual diagnostics (heavy)
        _, rd_val = _run_cached_step(
            "Residual diagnostics", "residual_diagnostics",
            lambda cp: cp["residual_result"],
            lambda: rd_mod.residual_diagnostics(
                data, spec, confounders, refined_edges, primary_effect,
                budget, protected_columns=protected_columns),
            lambda v: {
                "residual_result": v,
                "corrected_effect": v.get("corrected_effect"),
            })
        step_outputs["residual_diagnostics"] = rd_val
        result["steps"]["residual_diagnostics"] = rd_val
        corrected = rd_val.get("corrected_effect") if isinstance(rd_val, dict) else None
        if corrected is not None:
            primary_effect = corrected
        reclaim()

        # GRF (heavy)
        if spec.grf_configs:
            cp = _cp_load("grf")
            if cp is not None:
                step_outputs["grf"] = cp["grf_result"]
            else:
                report(0, "GRF heterogeneity")
                grf_result = grf_mod.grf_heterogeneity(
                    data, spec, confounders, budget, checkpoint,
                    pool_submit=self._pool_submit)
                _cp_save("grf", {"grf_result": grf_result})
                step_outputs["grf"] = grf_result
            result["steps"]["grf"] = step_outputs.get("grf")
            reclaim()

        # Null-finding diagnostics
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
                nd_result = nd_mod.null_diagnostics(
                    data, spec, confounders,
                    primary_effect, primary_ci, estimation_results,
                    step_outputs.get("sensitivity", {}), budget)
                _cp_save("null_diagnostics", {"nd_result": nd_result})
                result["steps"]["null_diagnostics"] = nd_result

        # Step 13 — Externalization
        if spec.externalization:
            cp = _cp_load("externalization")
            if cp is not None:
                report(0.95, "Externalization (cached)")
                result["steps"]["externalization"] = cp["ext_result"]
            else:
                report(0.95, "Externalization tests")
                ext_result = ext_mod.externalization(data, spec, result)
                _cp_save("externalization", {"ext_result": ext_result})
                result["steps"]["externalization"] = ext_result

        # Final assembly
        result["final_effect"] = primary_effect
        result["ci_crosses_zero"] = bool(
            primary_ci is not None and primary_ci[0] * primary_ci[1] <= 0
        )
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
            {"field_name": d.field_name, "generator_value": d.generator_value,
             "compiler_value": d.compiler_value, "resolution": d.resolution}
            for d in spec.discrepancy_log
        ]

        # FIX E11: count refutation errors, emit marker
        refutation_results = result.get("steps", {}).get("refutations", {})
        refutation_error_count = sum(
            1 for v in refutation_results.values()
            if isinstance(v, dict) and "error" in v
        )
        if refutation_error_count > 0:
            result["refutation_errors"] = refutation_error_count
            result["refutation_error_warning"] = (
                f"{refutation_error_count} refutation(s) failed — "
                f"robustness assessment is incomplete"
            )

        if checkpoint:
            from datetime import datetime
            checkpoint.save_run_meta({
                "spec": spec.to_dict(),
                "status": "completed",
                "completed_at": datetime.utcnow().isoformat(),
                "_score": datetime.utcnow().timestamp(),
                "result": result,
            })

        report(1.0, "Pipeline complete")
        return result
