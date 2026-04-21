import logging
import pandas as pd
from concurrent.futures import Future
from dto.causal_verification_request import GrfConfig, TreatmentForm
from econml.dml import CausalForestDML
from lightgbm import LGBMClassifier, LGBMRegressor

from .memory_budget import (
    GRF_ESTIMATORS, GRF_MIN_LEAF, LGBM_DEFAULTS, RANDOM_STATE,
    _data_mb, _rss_mb,
)

logger = logging.getLogger(__name__)


def grf_heterogeneity(data, spec, confounders, budget=None, checkpoint=None,
                      pool_submit=None):
    # FIX E6: use original_treatment for GRF — CATEs should be in
    # the original treatment's scale, not rewritten codes
    grf_treatment = spec.original_treatment or spec.treatment
    if grf_treatment not in data.columns:
        grf_treatment = spec.treatment

    def _fit_one_grf(cfg: GrfConfig) -> dict:
        try:
            lgbm_kw = budget.lgbm_defaults() if budget else LGBM_DEFAULTS
            grf_n_est = budget.param("grf_estimators") if budget else GRF_ESTIMATORS
            grf_n_est = max(4, (grf_n_est // 4) * 4)

            fit_data = data
            discrete = spec.treatment_form != TreatmentForm.CONTINUOUS
            if budget and len(data) > 100_000:
                rss = _rss_mb()
                data_mb_val = _data_mb(data)
                n_levels = data.raw[grf_treatment].nunique()
                grf_mem_mult = 200 * n_levels if discrete else 200
                projected = rss + data_mb_val * grf_mem_mult
                target = budget.container_mb * 0.80
                if projected > target:
                    safe_mb = max(0, target - rss) / grf_mem_mult
                    frac = min(1.0, safe_mb / data_mb_val) if data_mb_val > 0 else 1.0
                    min_frac = (500 * n_levels) / len(data) if len(data) > 0 else 1.0
                    frac = max(min_frac, frac)
                    if frac < 1.0:
                        fit_data = data.stratified_subsample(grf_treatment, frac)

            enc = fit_data.encoded
            X_grf = enc[cfg.modifier_columns].values
            Y = enc[spec.outcome].values
            T = enc[grf_treatment].values
            W = enc[confounders].values

            discrete = spec.treatment_form != TreatmentForm.CONTINUOUS
            model_t = LGBMClassifier(**lgbm_kw) if discrete else LGBMRegressor(**lgbm_kw)
            grf = CausalForestDML(
                model_y=LGBMRegressor(**lgbm_kw),
                model_t=model_t,
                discrete_treatment=discrete,
                n_estimators=grf_n_est,
                min_samples_leaf=GRF_MIN_LEAF,
                random_state=RANDOM_STATE,
            )
            grf.fit(Y=Y, T=T, X=X_grf, W=W)
            cates = grf.effect(X=X_grf)

            slices = {}
            for col, method in cfg.slicing.items():
                if col not in fit_data.columns:
                    continue
                col_vals = fit_data[col]
                if method == "unique":
                    for val in sorted(col_vals.unique()):
                        mask = (col_vals == val).values
                        subset = cates[mask]
                        slices[f"{col}={val}"] = {
                            "mean_cate": float(subset.mean()),
                            "std_cate": float(subset.std()),
                            "n": int(len(subset)),
                        }
                elif method == "quartile":
                    q_col = pd.qcut(fit_data.raw[col], 4, duplicates="drop")
                    for q, grp_idx in fit_data.raw.groupby(q_col).groups.items():
                        positions = fit_data.raw.index.get_indexer(grp_idx)
                        slices[f"{col}={q}"] = {
                            "mean_cate": float(cates[positions].mean()),
                            "std_cate": float(cates[positions].std()),
                            "n": int(len(positions)),
                        }

            importances = {
                name: float(imp)
                for name, imp in zip(cfg.modifier_columns, grf.feature_importances_)
            }
            return {
                "config_id": cfg.id,
                "slices": slices,
                "feature_importances": importances,
                "mean_cate": float(cates.mean()),
                "std_cate": float(cates.std()),
            }
        except Exception as e:
            return {"config_id": cfg.id, "error": str(e)}

    results = []
    futures: dict[str, Future] = {}
    for cfg in spec.grf_configs:
        cached = checkpoint.load(f"grf:{cfg.id}") if checkpoint else None
        if cached is not None:
            results.append(cached)
        else:
            if pool_submit:
                futures[cfg.id] = pool_submit(budget, lambda c=cfg: _fit_one_grf(c))
            else:
                f: Future = Future()
                try:
                    f.set_result(_fit_one_grf(cfg))
                except Exception as e:
                    f.set_exception(e)
                futures[cfg.id] = f
    for cid, f in futures.items():
        r = f.result()
        results.append(r)
        if checkpoint:
            checkpoint.save(f"grf:{cid}", r)
    return results
