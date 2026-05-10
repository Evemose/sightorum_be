import logging
import numpy as np
import pandas as pd
from dto.causal_verification_request import EstimationVariant, TreatmentForm
from econml.dml import LinearDML
from econml.inference import BootstrapInference
from lightgbm import LGBMClassifier, LGBMRegressor
from typing import Any, Optional

from .memory_budget import CI_ALPHA
from .pipeline_utils import apply_variant_filter

logger = logging.getLogger(__name__)


def run_estimation_variant(data, variant, refined_edges, dag_nx, outcome_col,
                           budget=None):
    filtered = apply_variant_filter(data, variant)
    df = filtered.encoded.copy()
    treatment_col = variant.treatment_column
    W_cols = variant.w_columns
    discrete = variant.treatment_form != TreatmentForm.CONTINUOUS

    code_to_label: dict[int, str] = {}

    if variant.treatment_form == TreatmentForm.BINARY_THRESHOLD and variant.threshold_value is not None:
        bin_col = f"_bin_{treatment_col}_{variant.threshold_value}"
        df[bin_col] = (df[treatment_col] > variant.threshold_value).astype(int)
        refined_edges = [
            (bin_col if s == variant.treatment_column else s,
             bin_col if d == variant.treatment_column else d)
            for s, d in refined_edges
        ]
        code_to_label = {
            0: f"below_or_equal_{variant.threshold_value}",
            1: f"above_{variant.threshold_value}",
        }
        treatment_col = bin_col
    elif discrete and treatment_col in df.columns:
        raw_unique = list(filtered.raw[treatment_col].dropna().unique())
        raw_alpha = sorted(raw_unique)
        # Honor user-specified reference_category by placing it at code 0.
        # EconML's LinearDML(discrete_treatment=True) treats the lowest code
        # as the reference; without explicit ordering the engine would silently
        # use the alphabetically-first category instead of the user's choice.
        ref = variant.reference_category
        if ref is not None and ref in raw_unique:
            ordered = [ref] + [v for v in raw_alpha if v != ref]
        else:
            if ref is not None:
                logger.warning(
                    "Variant %s: reference_category %r not in filtered data "
                    "values %s — falling back to alphabetically-first reference",
                    variant.id, ref, raw_alpha)
            ordered = raw_alpha
        val_to_code = {v: i for i, v in enumerate(ordered)}
        df[treatment_col] = filtered.raw[treatment_col].map(val_to_code).astype("Int64")
        code_to_label = {i: str(v) for i, v in enumerate(ordered)}

    return estimate_dml(
        df, variant, W_cols, treatment_col, discrete, refined_edges, outcome_col,
        code_to_label, budget, filtered=filtered,
    )


def _is_binary_outcome(y_values) -> bool:
    unique = pd.unique(pd.Series(y_values).dropna())
    if len(unique) != 2:
        return False
    s = set(float(x) for x in unique)
    return s == {0.0, 1.0}


def estimate_dml(df, variant, w_cols, treatment_col, discrete, refined_edges,
                 outcome_col, code_to_label=None, budget=None, filtered=None):
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
        ci_lo_arr, ci_hi_arr = dml.effect_interval(alpha=CI_ALPHA)
        ci_lo = float(ci_lo_arr.mean())
        ci_hi = float(ci_hi_arr.mean())

        binary_outcome = _is_binary_outcome(Y)
        ci_warning = None
        if binary_outcome:
            if ci_lo < -1.0 or ci_hi > 1.0:
                ci_warning = (
                    f"CI [{ci_lo:.4f}, {ci_hi:.4f}] exceeds physical bound "
                    f"[-1, 1] for binary outcome; bootstrap extrapolation "
                    f"produced infeasible bounds — likely due to extreme "
                    f"treatment imbalance. Clamping for downstream consumers."
                )
                logger.warning("Variant %s: %s", variant.id, ci_warning)
                ci_lo_clamped = max(-1.0, min(1.0, ci_lo))
                ci_hi_clamped = max(-1.0, min(1.0, ci_hi))
            else:
                ci_lo_clamped, ci_hi_clamped = ci_lo, ci_hi
        else:
            ci_lo_clamped, ci_hi_clamped = ci_lo, ci_hi

        ci = (ci_lo_clamped, ci_hi_clamped)
        ci_raw = (ci_lo, ci_hi)

        category_effects = None
        category_effects_raw = None
        category_effects_warning = None
        category_effects_degenerate = False
        if discrete and code_to_label:
            cme = dml.const_marginal_effect()
            if cme.ndim == 1:
                cme = cme.reshape(-1, 1)
            codes = sorted(code_to_label.keys())
            ref_code = codes[0]
            ref_label = code_to_label.get(ref_code, str(ref_code))
            non_ref_codes = codes[1:]
            is_binary_threshold = (variant.treatment_form == TreatmentForm.BINARY_THRESHOLD)
            col_name = variant.treatment_column
            category_effects = {}
            category_effects_raw = {}
            for i, code in enumerate(non_ref_codes):
                if i < cme.shape[1]:
                    label = code_to_label.get(code, str(code))
                    key = label if is_binary_threshold else f"{col_name}={label}"
                    eff_raw = float(cme[:, i].mean())
                    category_effects_raw[key] = eff_raw
                    category_effects[key] = eff_raw
            ref_key = ref_label if is_binary_threshold else f"{col_name}={ref_label}"
            category_effects[ref_key] = 0.0
            category_effects_raw[ref_key] = 0.0

            # Physical-feasibility check for binary outcomes.
            # |E[Y|do(T=k)] - E[Y|do(T=ref)]| ≤ 1 by definition (Y ∈ {0,1});
            # values outside [-1, 1] indicate numerical degeneracy in the DML
            # final stage (rank-deficient W, quasi-deterministic propensity,
            # or extreme treatment imbalance). Clamp + flag.
            if binary_outcome:
                out_of_bounds = {
                    k: v for k, v in category_effects_raw.items()
                    if abs(v) > 1.0
                }
                if out_of_bounds:
                    category_effects_degenerate = True
                    category_effects_warning = (
                        f"{len(out_of_bounds)}/{len(category_effects_raw)} "
                        f"category effects outside [-1, 1] physical bound for "
                        f"binary outcome — DML final stage is numerically "
                        f"degenerate (likely rank-deficient W or quasi-"
                        f"deterministic propensity). Worst: "
                        f"{max(out_of_bounds.items(), key=lambda kv: abs(kv[1]))}. "
                        f"Clamping; do NOT interpret per-category contrasts."
                    )
                    logger.warning("Variant %s: %s", variant.id, category_effects_warning)
                    category_effects = {
                        k: max(-1.0, min(1.0, v))
                        for k, v in category_effects_raw.items()
                    }

    except Exception as e:
        logger.warning(f"Variant {variant.id} estimation failed: {e}")
        return {"variant_id": variant.id, "effect": None, "ci": None, "error": str(e)}

    result = {
        "variant_id": variant.id,
        "effect": effect,
        "ci": ci,
        "ci_raw": ci_raw,
        "treatment_column": treatment_col,
        "w_columns": w_cols,
        "n_obs": len(df),
        "discrete": discrete,
        "binary_outcome": binary_outcome,
    }
    if ci_warning:
        result["ci_warning"] = ci_warning
    if category_effects is not None:
        result["category_effects"] = category_effects
        if category_effects_raw is not None and category_effects_raw != category_effects:
            result["category_effects_raw"] = category_effects_raw
        if category_effects_warning:
            result["category_effects_warning"] = category_effects_warning
        if category_effects_degenerate:
            result["category_effects_degenerate"] = True
        # Surface the actual reference category used (after honoring user spec)
        if code_to_label:
            result["reference_category"] = code_to_label.get(0)
    return result
