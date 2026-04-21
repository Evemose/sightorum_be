import logging
import numpy as np
import pandas as pd
import ruptures

logger = logging.getLogger(__name__)


def structural_breaks(data, spec, effect, ci):
    work = pd.DataFrame({
        "_entity": data.raw[spec.structural_breaks[0].entity_column]
        if spec.structural_breaks else pd.Series(dtype="object"),
    })
    results = []
    for sb in spec.structural_breaks:
        if sb.entity_column not in data.columns or sb.temporal_column not in data.columns:
            results.append({"id": sb.id, "error": "column not found"})
            continue

        try:
            df_agg = pd.DataFrame({
                sb.entity_column: data.raw[sb.entity_column],
                "_period": pd.to_datetime(data.raw[sb.temporal_column]).dt.to_period(
                    sb.temporal_grain[0].upper()),
                spec.outcome: data.encoded[spec.outcome],
                spec.treatment: data.encoded[spec.treatment],
            })
            agg = (
                df_agg.groupby([sb.entity_column, "_period"])
                .agg(
                    _rate=(spec.outcome, "mean"),
                    _mean_t=(spec.treatment, "mean"),
                    _n=(spec.outcome, "count"),
                )
                .reset_index()
            )
            agg["_ts"] = agg["_period"].dt.to_timestamp()

            matches = []
            for entity in agg[sb.entity_column].unique():
                ed = agg[agg[sb.entity_column] == entity].sort_values("_period")
                series = ed["_rate"].values
                if len(series) < sb.min_obs_per_period:
                    continue
                try:
                    brks = ruptures.Pelt(model="rbf").fit(series).predict(
                        pen=sb.pelt_penalty
                    )
                except Exception:
                    continue
                for bi in brks[:-1]:
                    if bi < 3 or bi > len(series) - 3:
                        continue
                    break_date = str(ed.iloc[bi]["_ts"])
                    pre = float(series[max(0, bi - 6):bi].mean())
                    post = float(series[bi:min(len(series), bi + 6)].mean())
                    actual = post - pre
                    pre_t = float(ed.iloc[max(0, bi - 3):bi]["_mean_t"].mean())
                    post_t = float(ed.iloc[bi:bi + 3]["_mean_t"].mean())
                    t_delta = post_t - pre_t
                    predicted = t_delta * effect if effect else 0
                    direction_match = bool(np.sign(actual) == np.sign(predicted)) if predicted != 0 else False
                    within_ci = False
                    if ci and t_delta != 0:
                        within_ci = bool(ci[0] * t_delta <= actual <= ci[1] * t_delta)

                    matches.append({
                        "entity": str(entity),
                        "break_date": break_date,
                        "actual_change": float(actual),
                        "treatment_delta": float(t_delta),
                        "predicted_change": float(predicted),
                        "direction_match": direction_match,
                        "within_ci": within_ci,
                    })

            ci_matches = [m for m in matches if m["within_ci"]]
            dir_matches = [m for m in matches if m["direction_match"]]
            tier = 1 if len(ci_matches) >= 2 else (2 if len(dir_matches) >= 1 else 3)

            results.append({
                "id": sb.id,
                "total_breaks": len(matches),
                "direction_matches": len(dir_matches),
                "ci_matches": len(ci_matches),
                "tier": tier,
                "matches": matches,
            })
        except Exception as e:
            results.append({"id": sb.id, "error": str(e)})
    return results
