import logging
import math
import pandas as pd
from dto.causal_verification_request import UnmeasuredMethod

logger = logging.getLogger(__name__)


def unmeasured_confounding(data, spec, estimation_results):
    enc = data.encoded
    baseline_rate = float(enc[spec.outcome].mean())
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
            v_result = estimation_results.get(uc.variant_id, {})
            is_binary = v_result.get("discrete", False)
            if is_binary:
                multiplier = 1.0
            else:
                raw_t = data[spec.treatment]
                if pd.api.types.is_numeric_dtype(raw_t):
                    iqr = float(raw_t.quantile(0.75) - raw_t.quantile(0.25))
                    std = float(raw_t.std())
                    multiplier = iqr if iqr > 0 else std if std > 0 else 1.0
                else:
                    multiplier = 1.0
            abs_eff = abs(effect * multiplier)
            rr = (baseline_rate + abs_eff) / baseline_rate if baseline_rate > 0 else 1
            e_point = rr + math.sqrt(rr * (rr - 1)) if rr > 1 else 1.0

            e_ci = 1.0
            if ci:
                abs_ci = abs(ci[0] * multiplier)
                rr_ci = max(1.0, (baseline_rate + abs_ci) / baseline_rate) if baseline_rate > 0 else 1
                e_ci = rr_ci + math.sqrt(rr_ci * (rr_ci - 1)) if rr_ci > 1 else 1.0

            results.append({
                "variant_id": uc.variant_id,
                "method": "E_VALUE",
                "rr": float(rr),
                "e_value_point": float(e_point),
                "e_value_ci": float(e_ci),
                "null_hypothesis": uc.null_hypothesis,
            })
        else:
            results.append({
                "variant_id": uc.variant_id,
                "method": "ROSENBAUM_BOUNDS",
                "notes": uc.notes,
            })
    return results
