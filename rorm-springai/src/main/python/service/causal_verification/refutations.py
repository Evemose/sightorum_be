import dowhy
import logging
import numpy as np
from concurrent.futures import Future
from dto.causal_verification_request import RefutationType, TreatmentForm
from lightgbm import LGBMClassifier, LGBMRegressor
from service.pipeline_dataframe import PipelineDataFrame
from sklearn.linear_model import LinearRegression
from typing import Any

from .memory_budget import (
    LGBM_DEFAULTS, REFUTATION_SIMULATIONS, _data_mb, _rss_mb, reclaim,
)
from .pipeline_utils import replace_dag_node, run_dml_quick

logger = logging.getLogger(__name__)


def refutations_parallel(data, spec, dag_nx, primary_effect, budget,
                         checkpoint=None, pool_submit=None):
    discrete = spec.treatment_form != TreatmentForm.CONTINUOUS
    lgbm_kw = budget.lgbm_defaults() if budget else LGBM_DEFAULTS
    n_sims = budget.param("refutation_simulations") if budget else REFUTATION_SIMULATIONS

    refute_data = data
    if budget and len(data) > 100_000:
        rss = _rss_mb()
        data_mb_val = _data_mb(data)
        n_levels = data.raw[spec.treatment].nunique()
        refute_multiplier = 200 * n_levels / 27
        projected = rss + data_mb_val * refute_multiplier
        target = budget.container_mb * 0.80
        if projected > target:
            safe_mb = max(0, target - rss) / refute_multiplier
            frac = min(1.0, safe_mb / data_mb_val) if data_mb_val > 0 else 1.0
            min_frac = (500 * n_levels) / len(data) if len(data) > 0 else 1.0
            frac = max(min_frac, frac)
            if frac < 1.0:
                refute_data = data.stratified_subsample(spec.treatment, frac)

    def _build_model():
        model_t = LGBMClassifier(**lgbm_kw) if discrete else LGBMRegressor(**lgbm_kw)
        params = {
            "init_params": {
                "model_y": LGBMRegressor(**lgbm_kw),
                "model_t": model_t,
                "model_final": LinearRegression(),
                "discrete_treatment": discrete,
            },
            "fit_params": {},
        }
        # FIX E10: column names must match DAG nodes.
        # dowhy's placebo refuter calls np.isnan on the treatment column;
        # for string-valued categorical treatments this raises
        # "ufunc 'isnan' not supported". Pass numeric-encoded data, but
        # keep raw column names so the graph alignment still works.
        raw = refute_data.raw
        encoded = refute_data.encoded
        dowhy_df = raw.copy()
        for col in (spec.treatment, spec.outcome):
            if col in encoded.columns and col in refute_data.cat_columns:
                dowhy_df[col] = encoded[col].astype("float64")
        model = dowhy.CausalModel(
            data=dowhy_df, treatment=spec.treatment,
            outcome=spec.outcome, graph=dag_nx,
            effect_modifiers=[],
        )
        ident = model.identify_effect(proceed_when_unidentifiable=False)
        est = model.estimate_effect(
            ident, method_name="backdoor.econml.dml.DML",
            method_params=params,
            effect_modifiers=[],
        )
        return model, ident, est

    def _run_refutation(ref_type, method_name, **kwargs):
        model, ident, est = _build_model()
        r = model.refute_estimate(ident, est, method_name=method_name,
                                  num_simulations=n_sims, **kwargs)
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
            temporal_placebo(data, spec, dag_nx, primary_effect, budget,
                             pool_submit=pool_submit)),
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
            f: Future = Future()
            try:
                f.set_result(dispatch[ref_cfg.type]())
            except Exception as e:
                f.set_exception(e)
            futures[key] = f
            reclaim()
        else:
            f: Future = Future()
            f.set_exception(ValueError(
                f"refutation type {ref_cfg.type!r} has no dispatch handler; "
                f"supported: {[t.value for t in dispatch.keys()]}"
            ))
            futures[key] = f
    return futures


def collect_refutations(futures, spec, primary_effect, checkpoint=None):
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


def temporal_placebo(data, spec, dag_nx, primary_effect, budget=None,
                     pool_submit=None):
    # FIX E12: skip temporal placebo entirely for categorical treatments —
    # shifting entity identity codes in time is meaningless
    placebo_treatment = spec.original_treatment or spec.treatment
    if placebo_treatment not in data.columns:
        return {"error": f"treatment column '{placebo_treatment}' not in data",
                "skipped": True}

    if spec.treatment_form == TreatmentForm.CATEGORICAL and not spec.original_treatment:
        return {"error": "temporal placebo skipped: categorical treatment "
                         "without original continuous treatment",
                "skipped": True}

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

    lag_fractions = [0.10, 0.20, 0.33]
    shifts = []
    for frac in lag_fractions:
        lag = max(1, int(n * frac))
        shifts.append((f"forward_{lag}", lag))
        shifts.append((f"backward_{lag}", -lag))

    placebo_dag = replace_dag_node(
        dag_nx, placebo_treatment, f"_tp_{placebo_treatment}"
    )
    placebo_col = f"_tp_{placebo_treatment}"

    def _run_probe(label, lag):
        df = sorted_enc.copy()
        df[placebo_col] = df[placebo_treatment].shift(lag)
        df = df.dropna(subset=[placebo_col])
        if len(df) < 5:
            return {"label": label, "lag": lag,
                    "error": f"only {len(df)} rows after shift"}
        probe_data = PipelineDataFrame(df, df, [], {})
        effect = run_dml_quick(
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

    if pool_submit:
        probe_futures = [
            pool_submit(budget, lambda l=label, g=lag: _run_probe(l, g))
            for label, lag in shifts
        ]
    else:
        probe_futures = []
        for label, lag in shifts:
            f: Future = Future()
            try:
                f.set_result(_run_probe(label, lag))
            except Exception as e:
                f.set_exception(e)
            probe_futures.append(f)
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
