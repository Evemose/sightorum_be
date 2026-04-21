import numpy as np
from lightgbm import LGBMRegressor
from sklearn.model_selection import cross_val_score
from typing import Any

from .memory_budget import LGBM_DEFAULTS


def quality_gates(data, spec, confounders, effect, ci):
    gates = spec.gates
    result: dict[str, Any] = {}
    enc = data.encoded

    outcome_r2 = float(np.mean(cross_val_score(
        LGBMRegressor(**LGBM_DEFAULTS), enc[confounders], enc[spec.outcome],
        cv=5, scoring="r2")))
    treatment_r2 = float(np.mean(cross_val_score(
        LGBMRegressor(**LGBM_DEFAULTS), enc[confounders], enc[spec.treatment],
        cv=5, scoring="r2")))

    treatment_status = "pass"
    if treatment_r2 < gates.nuisance_r2.treatment_flag:
        treatment_status = "flag"
    elif treatment_r2 > gates.nuisance_r2.treatment_structural_max_r2:
        # FIX E9: distinguish rewritten near-1.0 from degenerate first stage
        if spec.original_treatment and spec.original_treatment != spec.treatment:
            treatment_status = "structural_rewrite"
        else:
            treatment_status = "structural"

    result["nuisance_r2"] = {
        "outcome_r2": outcome_r2,
        "treatment_r2": treatment_r2,
        "outcome_status": "flag" if outcome_r2 < gates.nuisance_r2.outcome_flag
        else "pass",
        "treatment_status": treatment_status,
    }

    if effect is not None:
        direction_ok = np.sign(effect) == gates.sanity.expected_direction
        magnitude = abs(effect)
        result["sanity"] = {
            "direction_ok": direction_ok,
            "effect_magnitude": magnitude,
            "flag_magnitude": gates.sanity.flag_magnitude,
            "status": "flag" if magnitude > gates.sanity.flag_magnitude
            else "pass",
        }
        if not direction_ok:
            result["sanity"]["warning"] = "Effect direction does not match expected"

    return result
