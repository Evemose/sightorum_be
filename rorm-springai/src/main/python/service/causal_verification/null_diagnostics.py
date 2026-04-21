import logging
import math
import numpy as np
from dto.causal_verification_request import TreatmentForm
from econml.dml import LinearDML
from lightgbm import LGBMClassifier, LGBMRegressor
from scipy.stats import norm
from typing import Any

from .memory_budget import CI_ALPHA, LGBM_DEFAULTS

logger = logging.getLogger(__name__)


def null_diagnostics(data, spec, confounders, primary_effect, primary_ci,
                     estimation_results, sensitivity_result, budget=None):
    result: dict[str, Any] = {}
    result["absorption_curve"] = absorption_curve(
        data, spec, confounders, sensitivity_result, budget)
    result["power_analysis"] = power_analysis(
        data, spec, primary_effect, primary_ci)
    result["subpopulation_edges"] = subpopulation_edge_scan(
        estimation_results)
    return result


def absorption_curve(data, spec, confounders, sensitivity_result, budget=None,
                     pool_submit=None):
    from concurrent.futures import Future
    drops = sensitivity_result.get("confounder_drops", [])
    drop_map = {d["column"]: abs(d.get("deviation_pct") or 0) for d in drops}
    ordered = sorted(confounders, key=lambda c: drop_map.get(c, 0), reverse=True)

    discrete = spec.estimation_variants[0].treatment_form != TreatmentForm.CONTINUOUS
    enc = data.encoded
    Y = enc[spec.outcome].values
    T = enc[spec.treatment].values
    model_t_cls = LGBMClassifier if discrete else LGBMRegressor
    lgbm_kw = budget.lgbm_defaults() if budget else LGBM_DEFAULTS

    def _fit(w_cols: list[str]) -> float | None:
        try:
            W = enc[w_cols].values if w_cols else None
            dml = LinearDML(
                model_y=LGBMRegressor(**lgbm_kw),
                model_t=model_t_cls(**lgbm_kw),
                discrete_treatment=discrete,
            )
            dml.fit(Y, T, W=W)
            return float(dml.effect().mean())
        except Exception as e:
            logger.warning("Absorption curve fit failed (W=%s): %s", w_cols, e)
            return None

    def _submit(fn):
        if pool_submit:
            return pool_submit(budget, fn)
        f = Future()
        try:
            f.set_result(fn())
        except Exception as e:
            f.set_exception(e)
        return f

    valid_cols = [c for c in ordered if c in enc.columns]
    futures: list[tuple[str | None, Future]] = [
        (None, _submit(lambda: _fit([])))
    ]
    for i, col in enumerate(valid_cols):
        w_up_to = list(valid_cols[:i + 1])
        futures.append((col, _submit(lambda w=w_up_to: _fit(w))))

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


def power_analysis(data, spec, primary_effect, primary_ci):
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


def subpopulation_edge_scan(estimation_results, z_threshold: float = 1.0):
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
