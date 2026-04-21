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

    return estimate_dml(
        df, variant, W_cols, treatment_col, discrete, refined_edges, outcome_col,
        code_to_label, budget,
    )


def estimate_dml(df, variant, w_cols, treatment_col, discrete, refined_edges,
                 outcome_col, code_to_label=None, budget=None):
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

        category_effects = None
        if discrete and code_to_label:
            cme = dml.const_marginal_effect()
            if cme.ndim == 1:
                cme = cme.reshape(-1, 1)
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
