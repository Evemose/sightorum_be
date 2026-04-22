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
        raw_vals = sorted(filtered.raw[treatment_col].dropna().unique())
        df[treatment_col] = df[treatment_col].astype("category").cat.codes
        codes = sorted(df[treatment_col].dropna().unique())
        for code, label in zip(codes, raw_vals):
            code_to_label[int(code)] = str(label)

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
            for i, code in enumerate(non_ref_codes):
                if i < cme.shape[1]:
                    label = code_to_label.get(code, str(code))
                    key = label if is_binary_threshold else f"{col_name}={label}"
                    category_effects[key] = float(cme[:, i].mean())
            ref_key = ref_label if is_binary_threshold else f"{col_name}={ref_label}"
            category_effects[ref_key] = 0.0

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
    return result
