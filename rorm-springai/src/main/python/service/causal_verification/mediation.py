import logging
from dto.causal_verification_request import TreatmentForm
from econml.dml import LinearDML
from lightgbm import LGBMClassifier, LGBMRegressor

from .memory_budget import LGBM_DEFAULTS

logger = logging.getLogger(__name__)


def mediation(data, spec, confounders, estimation_results, refined_edges):
    results = []
    variant_map = {v.id: v for v in spec.estimation_variants}
    for med in spec.mediation:
        total_est = estimation_results.get(med.total_variant_id, {})
        total_effect = total_est.get("effect")
        if total_effect is None:
            results.append({"mediator": med.mediator, "error": "total variant not found"})
            continue

        direct_est = estimation_results.get(med.direct_variant_id, {})
        direct_effect = direct_est.get("effect")

        total_v = variant_map.get(med.total_variant_id)
        direct_v = variant_map.get(med.direct_variant_id)
        if total_v is not None and direct_v is not None:
            def _filter_key(v):
                if v.filter is None:
                    return None
                from dataclasses import asdict
                return str(asdict(v.filter))

            if _filter_key(total_v) != _filter_key(direct_v):
                results.append({
                    "mediator": med.mediator,
                    "error": (
                        "total and direct variants have different filters — "
                        "their effects are on different subpopulations and "
                        "cannot be subtracted for mediation decomposition"
                    ),
                })
                continue
            if total_v.treatment_column != direct_v.treatment_column:
                results.append({
                    "mediator": med.mediator,
                    "error": (
                        f"total and direct variants have different treatment "
                        f"columns ({total_v.treatment_column} vs "
                        f"{direct_v.treatment_column}) — effects are on "
                        f"different scales"
                    ),
                })
                continue

        if direct_effect is not None:
            mediated = total_effect - direct_effect
            fraction = mediated / total_effect if total_effect != 0 else 0
            results.append({
                "mediator": med.mediator,
                "pathway": med.pathway,
                "direct_effect": direct_effect,
                "mediated_effect": mediated,
                "fraction": fraction,
                "source": "estimation_variants",
            })
        else:
            enc = data.encoded
            discrete = spec.treatment_form != TreatmentForm.CONTINUOUS
            W_med = enc[confounders + [med.mediator]].values

            treatment_col = spec.original_treatment or spec.treatment
            if treatment_col not in enc.columns:
                treatment_col = spec.treatment

            try:
                model_t = LGBMClassifier(**LGBM_DEFAULTS) if discrete else LGBMRegressor(**LGBM_DEFAULTS)
                dml = LinearDML(
                    model_y=LGBMRegressor(**LGBM_DEFAULTS),
                    model_t=model_t,
                    discrete_treatment=discrete,
                )
                dml.fit(enc[spec.outcome].values, enc[treatment_col].values, W=W_med)
                direct_fit = float(dml.effect().mean())
                mediated = total_effect - direct_fit
                fraction = mediated / total_effect if total_effect != 0 else 0
                results.append({
                    "mediator": med.mediator,
                    "pathway": med.pathway,
                    "direct_effect": direct_fit,
                    "mediated_effect": mediated,
                    "fraction": fraction,
                    "source": "refit",
                })
            except Exception as e:
                results.append({"mediator": med.mediator, "error": str(e)})
    return results
