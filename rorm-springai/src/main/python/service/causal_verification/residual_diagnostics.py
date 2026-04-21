import logging
import networkx as nx
import numpy as np
from dto.causal_verification_request import TreatmentForm
from lightgbm import LGBMRegressor
from scipy.stats import pearsonr
from sklearn.model_selection import KFold
from statsmodels.stats.diagnostic import acorr_ljungbox
from statsmodels.stats.stattools import durbin_watson
from typing import Any

from .memory_budget import CI_ALPHA, LGBM_DEFAULTS, RANDOM_STATE
from .pipeline_utils import edges_to_nx, run_dml_quick

logger = logging.getLogger(__name__)

AUTO_CORRECTION_ABORT_DELTA_PCT = 50.0


def residual_diagnostics(data, spec, confounders, refined_edges, effect,
                         budget=None, protected_columns=None):
    result: dict[str, Any] = {}

    X_c = data.encoded_values(confounders)
    Y_v = data.encoded[spec.outcome].values
    T_v = data.encoded[spec.treatment].values
    y_res = np.zeros(len(data))
    t_res = np.zeros(len(data))
    lgbm_kw = budget.lgbm_defaults() if budget else LGBM_DEFAULTS
    kf = KFold(n_splits=5, shuffle=True, random_state=RANDOM_STATE)
    for tr, te in kf.split(X_c):
        m_y = LGBMRegressor(**lgbm_kw).fit(X_c[tr], Y_v[tr])
        m_t = LGBMRegressor(**lgbm_kw).fit(X_c[tr], T_v[tr])
        y_res[te] = Y_v[te] - m_y.predict(X_c[te])
        t_res[te] = T_v[te] - m_t.predict(X_c[te])
    final_res = y_res - (effect or 0) * t_res

    ac_results = []
    for ac in spec.residual_checks.autocorrelation:
        if ac.temporal_column in data.columns:
            sorted_idx = data.sort_values(ac.temporal_column).index
            dw = float(durbin_watson(final_res[sorted_idx]))
            lb = acorr_ljungbox(final_res[sorted_idx], lags=ac.lags, return_df=True)
            flagged = dw < ac.threshold or bool((lb["lb_pvalue"] < CI_ALPHA).any())
            ac_results.append({
                "temporal_column": ac.temporal_column,
                "durbin_watson": dw,
                "flagged": flagged,
            })
    result["autocorrelation"] = ac_results

    fc = spec.residual_checks.field_correlation
    correlations = []
    for col in fc.check_columns:
        if col not in data.columns:
            continue
        vals = data.encoded[col].values.astype(float)
        valid = ~np.isnan(vals)
        if valid.sum() < 100:
            continue
        r, _ = pearsonr(final_res[valid], vals[valid])
        if abs(r) > fc.threshold:
            is_meta = any(
                mc.column == col for mc in spec.residual_checks.metadata_correlation
            )
            correlations.append({
                "column": col, "correlation": float(r), "is_metadata": is_meta,
            })
    correlations.sort(key=lambda x: (-x.get("is_metadata", False), -abs(x["correlation"])))
    result["field_correlations"] = correlations

    # FIX E3: build exclusion set using protected_columns + DAG-derived sets
    mediator_cols = {m.column for m in spec.mediators_excluded}
    dag = edges_to_nx(refined_edges)
    descendants = nx.descendants(dag, spec.treatment) if dag.has_node(spec.treatment) else set()
    ancestors = nx.ancestors(dag, spec.treatment) if dag.has_node(spec.treatment) else set()
    excluded = mediator_cols | descendants | ancestors | {spec.treatment, spec.outcome}

    # FIX E3: include original_treatment and its network in exclusions
    if spec.original_treatment:
        excluded.add(spec.original_treatment)
        if dag.has_node(spec.original_treatment):
            excluded |= nx.descendants(dag, spec.original_treatment)
            excluded |= nx.ancestors(dag, spec.original_treatment)

    # FIX E3: merge protected_columns into exclusion set
    if protected_columns:
        excluded |= protected_columns

    logger.info("Auto-correction exclusions: mediators=%s, descendants=%s, "
                "ancestors=%s, protected=%s, total_excluded=%s",
                sorted(mediator_cols), sorted(descendants),
                sorted(ancestors), sorted(protected_columns or set()),
                sorted(excluded))

    corrections = []
    corrected_value = effect
    ac_cfg = spec.residual_checks.auto_correction
    eligible = [c for c in correlations if c["column"] not in excluded]
    logger.info("Auto-correction: %d correlated fields, %d eligible after exclusion: %s",
                len(correlations),
                len(eligible),
                [c["column"] for c in eligible])
    discrete = spec.treatment_form != TreatmentForm.CONTINUOUS
    ac_data = data
    if discrete and len(data) > 100_000:
        ac_data = data.stratified_subsample(spec.treatment, 100_000 / len(data))

    for idx, c in enumerate(eligible[:ac_cfg.max_iterations]):
        dag_aug = edges_to_nx(refined_edges + [(c["column"], spec.outcome)])
        corrected = run_dml_quick(ac_data, spec.treatment, spec.outcome, dag_aug,
                                  discrete=discrete)
        if corrected is not None:
            delta_frac = (abs(corrected - corrected_value) / abs(corrected_value)
                          if corrected_value else 0)

            # FIX E15: abort if first correction delta > 50%
            if idx == 0 and delta_frac > AUTO_CORRECTION_ABORT_DELTA_PCT / 100:
                logger.warning(
                    "Auto-correction: first correction delta %.1f%% > %.1f%% abort threshold, "
                    "stopping correction loop",
                    delta_frac * 100, AUTO_CORRECTION_ABORT_DELTA_PCT)
                corrections.append({
                    "field": c["column"],
                    "is_metadata": c.get("is_metadata", False),
                    "original": float(corrected_value),
                    "corrected": float(corrected),
                    "delta_pct": float(delta_frac * 100),
                    "aborted": True,
                    "abort_reason": f"first correction delta {delta_frac * 100:.1f}% > "
                                    f"{AUTO_CORRECTION_ABORT_DELTA_PCT}% threshold",
                })
                break

            corrections.append({
                "field": c["column"],
                "is_metadata": c.get("is_metadata", False),
                "original": float(corrected_value),
                "corrected": float(corrected),
                "delta_pct": float(delta_frac * 100),
            })
            if delta_frac * 100 < ac_cfg.stop_criterion_ci_pct:
                break
            corrected_value = corrected
    result["corrections"] = corrections
    result["corrected_effect"] = float(corrected_value) if corrections and not any(
        c.get("aborted") for c in corrections) else None

    meta_results = []
    for mc in spec.residual_checks.metadata_correlation:
        if mc.column not in data.columns:
            continue
        vals = data.encoded[mc.column].values.astype(float)
        valid = ~np.isnan(vals)
        if valid.sum() < 100:
            continue
        r, _ = pearsonr(final_res[valid], vals[valid])
        meta_results.append({
            "column": mc.column,
            "correlation": float(r),
            "threshold": mc.threshold,
            "flagged": abs(r) > mc.threshold,
            "alert_type": mc.alert_type,
        })
    result["metadata_correlations"] = meta_results

    return result
