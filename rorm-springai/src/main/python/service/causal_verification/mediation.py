import logging
from dto.causal_verification_request import TreatmentForm
from econml.dml import LinearDML
from lightgbm import LGBMClassifier, LGBMRegressor

from .memory_budget import LGBM_DEFAULTS

logger = logging.getLogger(__name__)


def mediation(data, spec, confounders, estimation_results, refined_edges):
    results = []
    for med in spec.mediation:
        total_est = estimation_results.get(med.total_variant_id, {})
        total_effect = total_est.get("effect")
        if total_effect is None:
            results.append({"mediator": med.mediator, "error": "total variant not found"})
            continue

        enc = data.encoded
        # FIX E4: use the actual treatment_form from spec (which is now
        # correctly updated by atomic positivity rewrite), not stale form
        discrete = spec.treatment_form != TreatmentForm.CONTINUOUS
        W_med = enc[confounders + [med.mediator]].values

        # FIX E4/E6: use original_treatment for mediation when available,
        # since mediation should measure the original causal pathway
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
            direct = float(dml.effect().mean())
            mediated = total_effect - direct
            fraction = mediated / total_effect if total_effect != 0 else 0
            results.append({
                "mediator": med.mediator,
                "pathway": med.pathway,
                "direct_effect": direct,
                "mediated_effect": mediated,
                "fraction": fraction,
            })
        except Exception as e:
            results.append({"mediator": med.mediator, "error": str(e)})
    return results
