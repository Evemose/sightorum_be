import logging
import numpy as np
import pandas as pd
from sklearn.linear_model import LogisticRegression
from statsmodels.stats.outliers_influence import variance_inflation_factor
from typing import Any

logger = logging.getLogger(__name__)


def range_checks(data, spec, confounders, estimation_results, budget=None):
    result: dict[str, Any] = {}

    drop_set = set()
    applied_drops = []
    for dp in spec.range_checks.vif.drop_pairs:
        drop_col = dp.get("drop")
        keep_col = dp.get("keep")
        if drop_col and drop_col in confounders:
            drop_set.add(drop_col)
            applied_drops.append({"keep": keep_col, "drop": drop_col})

    vif_cols = [c for c in confounders if c not in drop_set] + [spec.treatment]
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
        "drop_pairs_applied": applied_drops,
        "dropped_columns": sorted(drop_set),
    }

    t_enc = data.encoded[spec.treatment]
    is_categorical = spec.treatment in data.cat_columns
    if is_categorical:
        result["treatment_cv"] = None
        result["treatment_cv_note"] = (
            "CV undefined for categorical treatment — treatment column is "
            "label-encoded integers where CV is an artifact of alphabetical "
            "ordering, not a real coefficient of variation"
        )
    else:
        mean = float(t_enc.mean())
        if abs(mean) < 1e-10:
            result["treatment_cv"] = None
            result["treatment_cv_note"] = (
                f"CV undefined: treatment mean ≈ 0 ({mean:.2e})"
            )
        else:
            result["treatment_cv"] = float(t_enc.std() / mean)

    variant_map = {v.id: v for v in spec.estimation_variants}

    overlaps = []
    for ov in spec.range_checks.overlap:
        variant_cfg = variant_map.get(ov.variant_id)
        variant_res = estimation_results.get(ov.variant_id, {})
        try:
            if variant_cfg is not None and \
                    variant_cfg.treatment_form.value == "BINARY_THRESHOLD" and \
                    variant_cfg.threshold_value is not None:
                src_col = variant_cfg.treatment_column
                threshold = float(variant_cfg.threshold_value)
                if src_col not in data.encoded.columns:
                    overlaps.append({"variant_id": ov.variant_id,
                                     "error": f"source treatment column "
                                              f"'{src_col}' not in data"})
                    continue
                binary = (data.encoded[src_col] > threshold).astype(int)
                binarization = "threshold"
            else:
                t_col = variant_res.get("treatment_column", spec.treatment)
                if t_col not in data.encoded.columns:
                    overlaps.append({"variant_id": ov.variant_id,
                                     "error": f"treatment column '{t_col}' "
                                              f"not in data"})
                    continue
                raw_binary = data.encoded[t_col]
                if raw_binary.nunique() > 2:
                    overlaps.append({
                        "variant_id": ov.variant_id,
                        "error": f"overlap check requires binary treatment "
                                 f"but '{t_col}' has {int(raw_binary.nunique())} "
                                 f"unique values — configure a BINARY_THRESHOLD "
                                 f"variant or binarize explicitly",
                    })
                    continue
                binary = raw_binary
                binarization = "direct"
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
                "binarization": binarization,
                "n_treated": int(binary.sum()),
                "n_control": int((1 - binary).sum()),
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
