import logging
from service.pipeline_dataframe import PipelineDataFrame
from typing import Any

from .pipeline_utils import edges_to_nx, run_dml_quick

logger = logging.getLogger(__name__)


def sensitivity(data, spec, refined_edges, primary_effect, estimation_results,
                budget=None, pool_submit=None, protected_columns=None):
    result: dict[str, Any] = {}
    from dto.causal_verification_request import TreatmentForm
    discrete = spec.treatment_form != TreatmentForm.CONTINUOUS
    protected = protected_columns or set()

    def _submit(fn):
        if pool_submit:
            return pool_submit(budget, fn)
        from concurrent.futures import Future
        f = Future()
        try:
            f.set_result(fn())
        except Exception as e:
            f.set_exception(e)
        return f

    def _run_drop(drop):
        dag_v = edges_to_nx(
            [(s, d) for s, d in refined_edges
             if s != drop.column and d != drop.column]
        )
        est = run_dml_quick(data, spec.treatment, spec.outcome, dag_v, discrete=discrete)
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
        (drop, _submit(lambda d=drop: _run_drop(d)))
        for drop in spec.sensitivity.confounder_drops
    ]
    result["confounder_drops"] = [f.result() for _, f in drop_futures]

    def _run_add(add):
        if add.column not in data.columns:
            return {"column": add.column, "error": "column not in data"}
        dag_v = edges_to_nx(refined_edges + [(add.column, spec.outcome)])
        est = run_dml_quick(data, spec.treatment, spec.outcome, dag_v, discrete=discrete)
        deviation = (abs(est - primary_effect) / abs(primary_effect) * 100
                     if est is not None and primary_effect else None)
        return {
            "column": add.column,
            "reasoning": add.reasoning,
            "effect": float(est) if est is not None else None,
            "deviation_pct": float(deviation) if deviation is not None else None,
        }

    add_futures = [
        (add, _submit(lambda a=add: _run_add(a)))
        for add in spec.sensitivity.confounder_adds
    ]
    result["confounder_adds"] = [f.result() for _, f in add_futures]

    # FIX E5: use original_treatment for threshold_variants so thresholds
    # remain meaningful after categorical coarsening
    thresh_treatment = spec.original_treatment or spec.treatment
    thresh = []
    for tv in spec.sensitivity.threshold_variants:
        # FIX E5: skip if treatment column is in protected set (rewritten)
        if thresh_treatment in protected and thresh_treatment != (spec.original_treatment or spec.treatment):
            thresh.append({
                "threshold": tv.threshold,
                "effect": None,
                "error": f"treatment column '{thresh_treatment}' is protected (rewritten)",
                "n_treated": 0, "n_control": 0,
                "expected_n_treated": tv.expected_n_treated,
                "expected_n_control": tv.expected_n_control,
            })
            continue

        if thresh_treatment not in data.encoded.columns:
            thresh.append({
                "threshold": tv.threshold,
                "effect": None,
                "error": f"treatment column '{thresh_treatment}' not in data",
                "n_treated": 0, "n_control": 0,
                "expected_n_treated": tv.expected_n_treated,
                "expected_n_control": tv.expected_n_control,
            })
            continue

        col = f"_thresh_{tv.threshold}"
        bin_series = (data.encoded[thresh_treatment] > tv.threshold).astype(int)
        n_treated = int(bin_series.sum())
        n_control = int((~bin_series.astype(bool)).sum())
        aug_enc = data.encoded.copy()
        aug_enc[col] = bin_series
        aug_raw = data.raw.copy()
        aug_raw[col] = bin_series
        aug_data = PipelineDataFrame(aug_raw, aug_enc, data.cat_columns, data.encoders)
        dag_v = edges_to_nx(
            [(col if s == thresh_treatment else s,
              col if d == thresh_treatment else d)
             for s, d in refined_edges]
        )
        est = run_dml_quick(aug_data, col, spec.outcome, dag_v)
        thresh.append({
            "threshold": tv.threshold,
            "effect": float(est) if est is not None else None,
            "n_treated": n_treated,
            "n_control": n_control,
            "expected_n_treated": tv.expected_n_treated,
            "expected_n_control": tv.expected_n_control,
        })
    result["threshold_variants"] = thresh

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
