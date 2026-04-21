import logging
import numpy as np
import pandas as pd
from sklearn.linear_model import LogisticRegression
from statsmodels.stats.outliers_influence import variance_inflation_factor
from typing import Any

logger = logging.getLogger(__name__)


def range_checks(data, spec, confounders, estimation_results, budget=None):
    result: dict[str, Any] = {}

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

    t_enc = data.encoded[spec.treatment]
    cv = float(t_enc.std() / t_enc.mean()) if t_enc.mean() != 0 else 0
    result["treatment_cv"] = cv

    overlaps = []
    for ov in spec.range_checks.overlap:
        variant = estimation_results.get(ov.variant_id, {})
        t_col = variant.get("treatment_column", spec.treatment)
        try:
            if t_col.startswith("_bin_") and t_col not in data.columns:
                parts = t_col.split("_", 3)
                src_col = parts[2] if len(parts) >= 3 else spec.treatment
                threshold = float(parts[3]) if len(parts) >= 4 else 0
                binary = (data.encoded[src_col] > threshold).astype(int)
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
