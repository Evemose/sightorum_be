import logging
import math
import networkx as nx
import pandas as pd
from dataclasses import replace as dc_replace
from dto.causal_verification_request import (
    AllocationBias, CausalVerificationRequest, EstimationVariant,
    ExternalizationConfig, GrfConfig, TreatmentForm,
)
from service.pipeline_dataframe import PipelineDataFrame
from typing import Optional

from .memory_budget import _rss_mb
from .pipeline_utils import infer_treatment_form, trace

logger = logging.getLogger(__name__)

MAX_POSITIVITY_ABORT_DELTA = 0.50


def select_positivity_confounders(data, adjustment_set, treatment_col):
    import numpy as np
    cardinalities = {}
    for col in adjustment_set:
        if col == treatment_col or col not in data.columns:
            continue
        if col not in data.cat_columns:
            continue
        card = int(data.raw[col].nunique())
        if card > 1:
            cardinalities[col] = card

    if not cardinalities:
        return []

    cards = np.array(list(cardinalities.values()), dtype=float)
    cv = float(cards.std() / cards.mean()) if cards.mean() > 0 else 0
    n_checks = max(1, min(5, math.ceil(cv * 3)))

    sorted_cols = sorted(cardinalities, key=cardinalities.get, reverse=True)
    selected = sorted_cols[:n_checks]
    logger.info("Positivity confounders: cv=%.2f, n_checks=%d, selected=%s "
                "(cardinalities: %s)",
                cv, n_checks, selected,
                {c: cardinalities[c] for c in selected})
    return selected


def positivity_gate(data, spec, budget=None):
    pc = spec.positivity_check
    hierarchy = pc.treatment_hierarchy
    attempted_levels = []

    is_continuous_treatment = spec.treatment_form == TreatmentForm.CONTINUOUS
    if is_continuous_treatment and hierarchy == [spec.treatment]:
        report = {
            "attempted_levels": [{
                "level": spec.treatment,
                "verdict": "PASSED",
                "note": "continuous treatment — positivity assessed via "
                        "propensity overlap in range_checks, not cell counts",
            }],
            "final_level": spec.treatment,
            "original_n": len(data),
            "surviving_n": len(data),
        }
        return report, None, None, None

    for level_idx, treatment_col in enumerate(hierarchy):
        if treatment_col not in data.columns:
            attempted_levels.append({
                "level": treatment_col,
                "error": f"Column '{treatment_col}' not in data",
                "verdict": "SKIPPED",
            })
            continue

        confounders_to_check = select_positivity_confounders(
            data, spec.adjustment_set, treatment_col)

        if not confounders_to_check:
            attempted_levels.append({
                "level": treatment_col,
                "error": "No categorical confounders to check",
                "verdict": "SKIPPED",
            })
            continue

        treatment_vals = data.raw[treatment_col] if treatment_col in data.raw.columns \
            else data.encoded[treatment_col]
        n_treatment_levels = int(treatment_vals.nunique())

        total_n = len(data)
        sparse_mask = pd.Series(False, index=data.raw.index)
        all_sparse_cells = []
        total_possible_cells = 0
        total_surviving_cells = 0
        confounder_reports = []

        for confounder in confounders_to_check:
            confounder_vals = data.raw[confounder] if confounder in data.raw.columns \
                else data.encoded[confounder]

            ct = pd.crosstab(treatment_vals, confounder_vals)
            n_cells = ct.size
            n_confounder_levels = len(ct.columns)
            abs_floor = max(5, 500 // n_confounder_levels) if n_confounder_levels > 0 else 5
            n_nonempty = int((ct > 0).sum().sum())
            sparse_cells = []

            for c_val in ct.columns:
                stratum_total = int(ct[c_val].sum())
                stratum_expected = stratum_total / n_treatment_levels if n_treatment_levels > 0 else 0
                relative_floor = stratum_expected * pc.relative_threshold

                for t_val in ct.index:
                    count = int(ct.loc[t_val, c_val])
                    threshold = max(abs_floor, relative_floor)
                    if count < threshold:
                        sparse_cells.append({
                            "treatment": str(t_val),
                            "confounder": str(c_val),
                            "count": count,
                            "threshold": int(threshold),
                        })
                        sparse_mask |= (
                                (treatment_vals == t_val) & (confounder_vals == c_val)
                        )

            n_surviving = n_nonempty - len([s for s in sparse_cells if s["count"] > 0])
            total_possible_cells += n_cells
            total_surviving_cells += n_surviving
            all_sparse_cells.extend(sparse_cells)
            confounder_reports.append({
                "confounder": confounder,
                "n_cells": n_cells,
                "n_nonempty": n_nonempty,
                "n_surviving": n_surviving,
                "sparse_count": len(sparse_cells),
                "coverage_pct": round(n_surviving / n_cells * 100, 2) if n_cells > 0 else 0,
            })

        surviving_mask = ~sparse_mask
        surviving_n = int(surviving_mask.sum())
        coverage_pct = total_surviving_cells / total_possible_cells * 100 \
            if total_possible_cells > 0 else 0

        effective_threshold = max(30, 70 - 3 * math.sqrt(n_treatment_levels))

        logger.info(
            "Positivity level '%s': %d treatment levels, "
            "checked %d confounders [%s], "
            "cells: %d possible / %d surviving (%.1f%%), "
            "threshold=%.1f%%, rows trimmed: %d (%.1f%%)",
            treatment_col, n_treatment_levels,
            len(confounders_to_check),
            ", ".join(f"{c['confounder']}({c['n_cells']})" for c in confounder_reports),
            total_possible_cells, total_surviving_cells, coverage_pct,
            effective_threshold,
            total_n - surviving_n, (total_n - surviving_n) / total_n * 100,
        )

        level_report = {
            "level": treatment_col,
            "confounders_checked": confounder_reports,
            "total_possible_cells": total_possible_cells,
            "total_surviving_cells": total_surviving_cells,
            "sparse_cells_count": len(all_sparse_cells),
            "sparse_cells": all_sparse_cells[:50],
            "coverage_pct": round(coverage_pct, 2),
            "effective_threshold": round(effective_threshold, 1),
            "row_coverage_pct": round(surviving_n / total_n * 100, 2),
        }

        if coverage_pct >= effective_threshold:
            if budget and budget.container_mb > 0:
                data_mb_val = surviving_n * data.encoded.shape[1] * 8 / (1024 * 1024)
                rss = _rss_mb()
                grf_multiplier = 200 * n_treatment_levels / 27
                projected_grf = rss + data_mb_val * grf_multiplier
                mem_target = budget.container_mb * 0.80
                if projected_grf > mem_target:
                    subsample_needed = max(0, mem_target - rss) / (data_mb_val * grf_multiplier) \
                        if data_mb_val > 0 else 1
                    min_per_level = int(surviving_n * subsample_needed / n_treatment_levels) \
                        if n_treatment_levels > 0 else 0
                    if min_per_level < 500:
                        level_report["verdict"] = "COARSENED"
                        level_report["coarsen_reason"] = (
                            f"Memory: GRF projected {projected_grf:.0f} MB > "
                            f"target {mem_target:.0f} MB, subsample would "
                            f"leave ~{min_per_level} obs/level (min 500)"
                        )
                        attempted_levels.append(level_report)
                        continue

            level_report["verdict"] = "PASSED"
            attempted_levels.append(level_report)

            is_original_treatment = (treatment_col == spec.treatment)

            if is_original_treatment:
                if len(all_sparse_cells) == 0:
                    report = {
                        "attempted_levels": attempted_levels,
                        "final_level": treatment_col,
                        "original_n": total_n,
                        "surviving_n": total_n,
                    }
                    return report, None, None, None
                else:
                    report = {
                        "attempted_levels": attempted_levels,
                        "final_level": treatment_col,
                        "trimmed_cells": all_sparse_cells[:100],
                        "original_n": total_n,
                        "surviving_n": surviving_n,
                    }
                    return report, None, treatment_col, surviving_mask.tolist()
            else:
                rewritten = rewrite_variants_for_positivity(
                    spec, treatment_col, spec.treatment,
                    data, surviving_mask)
                report = {
                    "attempted_levels": attempted_levels,
                    "final_level": treatment_col,
                    "trimmed_cells": all_sparse_cells[:100],
                    "original_n": total_n,
                    "surviving_n": surviving_n,
                    "coarsened_treatment": True,
                }
                return report, rewritten, treatment_col, surviving_mask.tolist()
        else:
            level_report["verdict"] = "COARSENED"
            attempted_levels.append(level_report)

    last_level = attempted_levels[-1] if attempted_levels else None
    report = {
        "attempted_levels": attempted_levels,
        "final_level": None,
        "original_n": len(data),
        "surviving_n": 0,
        "failure": (
            f"Positivity violation at all granularity levels. "
            f"Finest surviving: {last_level['level'] if last_level else 'none'} "
            f"at {last_level['coverage_pct'] if last_level else 0}% coverage."
        ),
    }
    return report, None, None, None


def rewrite_variants_for_positivity(spec, final_treatment, original_treatment,
                                    data, surviving_mask):
    coarsened = original_treatment is not None and final_treatment != original_treatment

    if not coarsened:
        return list(spec.estimation_variants)

    fine_to_coarse = {}
    if original_treatment in data.raw.columns and final_treatment in data.raw.columns:
        surviving_data = data.raw[surviving_mask] if surviving_mask is not None else data.raw
        mapping = surviving_data[[original_treatment, final_treatment]].drop_duplicates()
        for _, row in mapping.iterrows():
            fine_to_coarse[str(row[original_treatment])] = str(row[final_treatment])
        trace("Fine→coarse mapping (%d entries): %s",
              len(fine_to_coarse),
              dict(list(fine_to_coarse.items())[:5]))

    new_form = TreatmentForm.CATEGORICAL

    rewritten = []
    for v in spec.estimation_variants:
        if v.treatment_form == TreatmentForm.BINARY_THRESHOLD:
            trace("Variant %s: dropped (BINARY_THRESHOLD meaningless after "
                  "treatment rewrite %s → %s)",
                  v.id, original_treatment, final_treatment)
            continue

        # FIX E1: always update both treatment_column AND treatment_form on variants
        if v.filter is not None:
            filt = v.filter
            if hasattr(filt, 'values') and filt.values and filt.column == original_treatment:
                coarse_values = set()
                for fv in filt.values:
                    mapped = fine_to_coarse.get(str(fv))
                    if mapped:
                        coarse_values.add(mapped)

                if len(coarse_values) < 2:
                    continue

                mapped_ref = fine_to_coarse.get(str(v.reference_category)) \
                    if v.reference_category else None
                if mapped_ref not in coarse_values:
                    mapped_ref = sorted(coarse_values)[0]
                new_v = dc_replace(v,
                                   treatment_column=final_treatment,
                                   treatment_form=new_form,
                                   threshold_value=None,
                                   filter=dc_replace(filt,
                                                     column=final_treatment,
                                                     values=sorted(coarse_values)),
                                   reference_category=mapped_ref)
                rewritten.append(new_v)
            else:
                rewritten.append(dc_replace(v,
                                            treatment_column=final_treatment,
                                            treatment_form=new_form,
                                            threshold_value=None))
        else:
            mapped_ref = fine_to_coarse.get(str(v.reference_category)) \
                if v.reference_category else v.reference_category
            rewritten.append(dc_replace(v,
                                        treatment_column=final_treatment,
                                        treatment_form=new_form,
                                        threshold_value=None,
                                        reference_category=mapped_ref))

    return rewritten


def apply_positivity_trim(data, surviving_mask):
    mask = pd.Series(surviving_mask, index=data.raw.index) if isinstance(surviving_mask, list) \
        else surviving_mask
    return data.filter_mask(mask)


def apply_positivity_rewrite(spec, rewritten_variants, final_treatment,
                             surviving_mask, data=None):
    """Atomically rewrite spec for positivity coarsening.

    FIX E1/E14: When treatment changes, this updates spec.treatment,
    spec.treatment_form, AND every variant's treatment_column + treatment_form.
    No partial state — either everything changes or nothing does.
    """
    new_variants = []
    for v in rewritten_variants:
        if isinstance(v, dict):
            new_variants.append(EstimationVariant.from_dict(v))
        else:
            new_variants.append(v)

    new_treatment = final_treatment if final_treatment else spec.treatment
    treatment_changed = new_treatment != spec.treatment

    # FIX E14: if treatment didn't change, don't mutate spec at all
    if not treatment_changed:
        return dc_replace(spec, estimation_variants=new_variants)

    # FIX E1: infer treatment_form from column dtype, not hardcoded
    if data is not None:
        new_form = infer_treatment_form(data, new_treatment)
    else:
        new_form = TreatmentForm.CATEGORICAL

    # FIX E1: ensure EVERY variant has consistent treatment_column + treatment_form
    consistent_variants = []
    for v in new_variants:
        if v.treatment_column != new_treatment or v.treatment_form != new_form:
            v = dc_replace(v, treatment_column=new_treatment, treatment_form=new_form)
        consistent_variants.append(v)

    new_grf_configs = []
    for cfg in spec.grf_configs:
        filtered_mods = [m for m in cfg.modifier_columns if m != new_treatment]
        if filtered_mods:
            new_grf_configs.append(GrfConfig(
                id=cfg.id,
                modifier_columns=filtered_mods,
                slicing={k: v for k, v in cfg.slicing.items() if k != new_treatment},
            ))

    # FIX E7: don't rewrite allocation_bias treatment_column —
    # user intent tracks original treatment across groupings
    new_ext = spec.externalization

    new_spec = dc_replace(spec,
                          treatment=new_treatment,
                          treatment_form=new_form,
                          original_treatment=spec.original_treatment or spec.treatment,
                          estimation_variants=consistent_variants,
                          grf_configs=new_grf_configs,
                          externalization=new_ext)

    # FIX E13: post-substitution cycle check happens in run_pipeline
    # after edge rewriting, not here (edges aren't available here)

    return new_spec
