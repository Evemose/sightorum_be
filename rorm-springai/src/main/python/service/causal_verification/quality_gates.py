import numpy as np
import pandas as pd
from lightgbm import LGBMClassifier, LGBMRegressor
from sklearn.model_selection import cross_val_score
from typing import Any

from .memory_budget import LGBM_DEFAULTS


def quality_gates(data, spec, confounders, effect, ci):
    gates = spec.gates
    result: dict[str, Any] = {}
    enc = data.encoded

    outcome_is_binary = (
            enc[spec.outcome].nunique() == 2
            and set(float(x) for x in pd.unique(enc[spec.outcome].dropna())) == {0.0, 1.0}
    )
    outcome_scoring = "accuracy" if outcome_is_binary else "r2"
    outcome_model_cls = LGBMClassifier if outcome_is_binary else LGBMRegressor

    outcome_score = float(np.mean(cross_val_score(
        outcome_model_cls(**LGBM_DEFAULTS), enc[confounders], enc[spec.outcome],
        cv=5, scoring=outcome_scoring)))

    treatment_is_categorical = spec.treatment in data.cat_columns
    if treatment_is_categorical:
        treatment_scoring = "accuracy"
        treatment_r2 = float(np.mean(cross_val_score(
            LGBMClassifier(**LGBM_DEFAULTS), enc[confounders], enc[spec.treatment],
            cv=5, scoring=treatment_scoring)))
        treatment_score_type = "accuracy"
    else:
        treatment_scoring = "r2"
        treatment_r2 = float(np.mean(cross_val_score(
            LGBMRegressor(**LGBM_DEFAULTS), enc[confounders], enc[spec.treatment],
            cv=5, scoring=treatment_scoring)))
        treatment_score_type = "r2"

    treatment_status = "pass"
    if treatment_r2 < gates.nuisance_r2.treatment_flag:
        treatment_status = "flag"
    elif treatment_r2 > gates.nuisance_r2.treatment_structural_max_r2:
        if spec.original_treatment and spec.original_treatment != spec.treatment:
            treatment_status = "structural_rewrite"
        else:
            treatment_status = "structural"

    outcome_status = ("flag" if outcome_score < gates.nuisance_r2.outcome_flag
                      else "pass")

    result["nuisance_r2"] = {
        "outcome_r2": outcome_score,
        "outcome_score_type": "accuracy" if outcome_is_binary else "r2",
        "treatment_r2": treatment_r2,
        "treatment_score_type": treatment_score_type,
        "outcome_status": outcome_status,
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
