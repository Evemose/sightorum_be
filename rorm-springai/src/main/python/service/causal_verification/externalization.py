import logging
import pandas as pd
from dto.causal_verification_request import TreatmentForm
from typing import Any

logger = logging.getLogger(__name__)


def externalization(data, spec, pipeline_result):
    result: dict[str, Any] = {}

    from service.tier_ordering import parse_ordering, evaluate_ordering

    grf_results = pipeline_result.get("steps", {}).get("grf")
    grf_effects: dict[str, float] = {}
    if grf_results:
        for grf_r in grf_results:
            if isinstance(grf_r, dict) and "slices" in grf_r:
                for key, val in grf_r["slices"].items():
                    grf_effects[key] = val["mean_cate"]

    tv_effects: dict[str, float] = {}
    tv_sample_sizes: dict[str, int] = {}
    for tv in (pipeline_result.get("steps", {})
            .get("sensitivity", {})
            .get("threshold_variants", [])):
        if tv.get("effect") is not None:
            key = str(tv["threshold"])
            tv_effects[key] = tv["effect"]
            tv_sample_sizes[key] = tv.get("n_control", 0)

    rankings = []
    for dr in spec.externalization.domain_rankings:
        entry: dict[str, Any] = {
            "ordering": dr.ordering,
            "source": dr.source,
            "scope": dr.scope,
            "expected_concordance": dr.expected_concordance,
        }
        try:
            ordering = parse_ordering(dr.ordering)
        except ValueError as e:
            entry["error"] = str(e)
            rankings.append(entry)
            continue

        sample_sizes: dict[str, int] = {}
        # FIX E8: check that tv_effects is non-empty before using it
        # as source — empty tv_effects from upstream breakage (E5) should
        # not silently produce wrong results
        if tv_effects and spec.treatment_form == TreatmentForm.CONTINUOUS:
            effects = tv_effects
            sample_sizes = tv_sample_sizes
            entry["source_type"] = "threshold_variants"
        else:
            primary_est = (pipeline_result.get("steps", {})
                           .get("estimation", {})
                           .get(spec.estimation_variants[0].id, {}))
            cat_effects = primary_est.get("category_effects", {})
            effects = cat_effects if cat_effects else grf_effects
            entry["source_type"] = "category_effects" if cat_effects else "grf_slices"
            if cat_effects:
                col = spec.treatment
                counts = data.raw[col].value_counts()
                for label in cat_effects:
                    val = label.split("=", 1)[1] if "=" in label else label
                    if val in counts.index:
                        sample_sizes[label] = int(counts[val])

        # FIX E8: flag when effects are empty (upstream breakage)
        if not effects:
            entry["error"] = ("no effect estimates available — "
                              "upstream step may have failed")
            entry["effects_used"] = {}
            rankings.append(entry)
            continue

        entry["effects_used"] = effects

        eval_result = evaluate_ordering(ordering, effects, sample_sizes=sample_sizes)
        entry.update(eval_result.to_dict())
        entry["pass"] = (eval_result.concordance >= dr.expected_concordance
                         if eval_result.total_pairs > 0 else None)

        rankings.append(entry)
    result["domain_rankings"] = rankings

    # FIX E7: use original_treatment for allocation_bias,
    # not the rewritten treatment column
    alloc_results = []
    for ab in spec.externalization.allocation_bias:
        alloc_treatment = ab.treatment_column
        # If alloc_treatment was rewritten to match spec.treatment and we
        # have original_treatment, use original for user-intent fidelity
        if (spec.original_treatment and
                alloc_treatment == spec.treatment and
                spec.original_treatment in data.columns):
            alloc_treatment = spec.original_treatment

        if alloc_treatment in data.columns and ab.grouping_column in data.columns:
            try:
                ct = pd.crosstab(
                    data[alloc_treatment], data[ab.grouping_column], normalize="index"
                )
                max_dev = float(ct.values.std())
                alloc_results.append({
                    "treatment_column": alloc_treatment,
                    "grouping_column": ab.grouping_column,
                    "max_deviation": max_dev,
                    "flagged": max_dev > ab.flag_threshold,
                })
            except Exception as e:
                alloc_results.append({
                    "treatment_column": alloc_treatment,
                    "grouping_column": ab.grouping_column,
                    "error": str(e),
                })
    result["allocation_bias"] = alloc_results

    return result
