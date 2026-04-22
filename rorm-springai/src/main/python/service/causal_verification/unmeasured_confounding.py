import logging
import math
import pandas as pd
from dto.causal_verification_request import UnmeasuredMethod

logger = logging.getLogger(__name__)


def _is_binary_outcome(series) -> bool:
    unique = pd.unique(series.dropna())
    if len(unique) != 2:
        return False
    return set(float(x) for x in unique) == {0.0, 1.0}


def _nearest_to_null_ci_bound(ci):
    lo, hi = ci
    if lo <= 0 <= hi:
        return 0.0
    return lo if abs(lo) < abs(hi) else hi


def unmeasured_confounding(data, spec, estimation_results):
    enc = data.encoded
    outcome_series = enc[spec.outcome]
    binary_outcome = _is_binary_outcome(outcome_series)
    baseline_rate = float(outcome_series.mean())
    results = []
    for uc in spec.unmeasured_confounding:
        variant = estimation_results.get(uc.variant_id)
        if variant is None:
            results.append({"variant_id": uc.variant_id, "error": "variant not found in estimation results"})
            continue
        effect = variant.get("effect")
        ci = variant.get("ci")
        if effect is None:
            results.append({"variant_id": uc.variant_id,
                            "error": f"variant estimation failed: {variant.get('error', 'unknown')}"})
            continue

        if uc.method == UnmeasuredMethod.E_VALUE:
            if not binary_outcome:
                results.append({
                    "variant_id": uc.variant_id,
                    "method": "E_VALUE",
                    "error": "E-value requires binary outcome — outcome "
                             f"'{spec.outcome}' has {outcome_series.nunique()} "
                             f"unique values and is not {{0, 1}}",
                    "null_hypothesis": uc.null_hypothesis,
                })
                continue

            treatment_form = variant.get("treatment_column_form") or spec.treatment_form
            is_binary_treatment = (
                    variant.get("binary_outcome") is not None
                    and (str(treatment_form).endswith("BINARY_THRESHOLD")
                         or (variant.get("category_effects") is not None
                             and len(variant["category_effects"]) == 2))
            )

            if is_binary_treatment:
                multiplier = 1.0
                scale_note = "per-unit (binary treatment: 0 → 1)"
            else:
                raw_t = data[spec.treatment]
                if pd.api.types.is_numeric_dtype(raw_t):
                    iqr = float(raw_t.quantile(0.75) - raw_t.quantile(0.25))
                    std = float(raw_t.std())
                    multiplier = iqr if iqr > 0 else (std if std > 0 else 1.0)
                    scale_note = f"per-IQR (continuous treatment, IQR={iqr:.3f})"
                else:
                    multiplier = 1.0
                    scale_note = "per-unit (non-numeric treatment, fallback)"

            abs_eff = abs(effect * multiplier)
            rr = (baseline_rate + abs_eff) / baseline_rate if baseline_rate > 0 else 1.0
            rr = max(1.0, rr)
            e_point = rr + math.sqrt(rr * (rr - 1)) if rr > 1 else 1.0

            e_ci = 1.0
            ci_bound_used = None
            if ci:
                ci_bound_used = _nearest_to_null_ci_bound(ci)
                if ci_bound_used == 0.0:
                    e_ci = 1.0
                else:
                    abs_ci_bound = abs(ci_bound_used * multiplier)
                    rr_ci = (baseline_rate + abs_ci_bound) / baseline_rate \
                        if baseline_rate > 0 else 1.0
                    rr_ci = max(1.0, rr_ci)
                    e_ci = rr_ci + math.sqrt(rr_ci * (rr_ci - 1)) if rr_ci > 1 else 1.0

            results.append({
                "variant_id": uc.variant_id,
                "method": "E_VALUE",
                "rr": float(rr),
                "e_value_point": float(e_point),
                "e_value_ci": float(e_ci),
                "null_hypothesis": uc.null_hypothesis,
                "baseline_rate": float(baseline_rate),
                "scale": scale_note,
                "ci_bound_used": float(ci_bound_used) if ci_bound_used is not None else None,
                "binary_outcome": True,
                "binary_treatment": bool(is_binary_treatment),
            })
        else:
            results.append({
                "variant_id": uc.variant_id,
                "method": "ROSENBAUM_BOUNDS",
                "notes": uc.notes,
                "error": "ROSENBAUM_BOUNDS not implemented — returning notes only",
                "implemented": False,
            })
    return results
