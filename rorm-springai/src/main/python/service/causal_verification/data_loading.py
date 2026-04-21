import logging
import networkx as nx
import pandas as pd
from dto.causal_verification_request import (
    CausalVerificationRequest, FilterOperator, TreatmentForm,
)
from service.pipeline_dataframe import PipelineDataFrame

logger = logging.getLogger(__name__)


def load_data(spec: CausalVerificationRequest, datasource) -> PipelineDataFrame:
    sql = spec.datasource.sql
    bind_vars = spec.datasource.bind_variables
    result = datasource.fetch(sql, bind_vars)
    data = result.dataframe.to_pandas()
    if spec.strip_columns:
        data = data.drop(
            columns=[c for c in spec.strip_columns if c in data.columns],
            errors="ignore",
        )
    return PipelineDataFrame.from_dataframe(data)


def validate_spec(spec: CausalVerificationRequest, data) -> None:
    errors: list[str] = []
    cols = set(data.columns)
    nrows = len(data)

    def _require_col(col: str, context: str) -> None:
        if col not in cols:
            errors.append(f"{context}: column '{col}' not found in data. "
                          f"Available: {sorted(cols)}")

    def _require_numeric(col: str, context: str) -> None:
        if col in cols and not pd.api.types.is_numeric_dtype(data.raw[col]):
            errors.append(f"{context}: column '{col}' has dtype "
                          f"'{data.raw[col].dtype}', expected numeric "
                          f"(will be label-encoded but semantically wrong)")

    def _validate_filter(f, context: str) -> None:
        if f.and_filters is not None:
            for i, sub in enumerate(f.and_filters):
                _validate_filter(sub, f"{context}.AND[{i}]")
            return
        if f.or_filters is not None:
            for i, sub in enumerate(f.or_filters):
                _validate_filter(sub, f"{context}.OR[{i}]")
            return
        if f.not_filter is not None:
            _validate_filter(f.not_filter, f"{context}.NOT")
            return
        if f.column is None:
            errors.append(f"{context}: leaf filter missing 'column'")
            return
        _require_col(f.column, context)
        if f.operator is None:
            errors.append(f"{context}: leaf filter missing 'operator'")
        elif f.operator in (FilterOperator.GT, FilterOperator.LT, FilterOperator.EQ):
            if not f.values:
                errors.append(f"{context}: operator {f.operator.value} "
                              f"requires at least one value")
        elif f.operator == FilterOperator.IN:
            if not f.values:
                errors.append(f"{context}: IN requires a non-empty values list")

    if nrows < 5:
        errors.append(f"data has {nrows} row(s), need at least 5 "
                      f"for 5-fold cross-validation in quality gates")

    _require_col(spec.outcome, "outcome")

    if spec.outcome in cols:
        _require_numeric(spec.outcome, "outcome")
        nan_count = int(data[spec.outcome].isna().sum())
        if nan_count > 0:
            errors.append(f"outcome column '{spec.outcome}' contains "
                          f"{nan_count} NaN value(s); pearsonr / DML will fail")
        if data[spec.outcome].nunique(dropna=True) < 2:
            errors.append(f"outcome column '{spec.outcome}' has fewer than "
                          f"2 unique values; DML estimation requires variance")

    for c in spec.adjustment_set:
        _require_col(c, "adjustment_set")

    parsed_edges: list[tuple[str, str]] = []
    edges = spec.dag_edges.replace("\n", ";").split(";")
    for edge_str in edges:
        edge_str = edge_str.strip()
        if not edge_str or "->" not in edge_str:
            continue
        src, dst = [s.strip() for s in edge_str.split("->", 1)]
        _require_col(src, f"dag_edges ('{src} -> {dst}')")
        _require_col(dst, f"dag_edges ('{src} -> {dst}')")
        parsed_edges.append((src, dst))

    if not parsed_edges:
        errors.append("dag_edges: no valid edges found (need at least one 'A -> B')")
    else:
        dag = nx.DiGraph(parsed_edges)
        if not nx.is_directed_acyclic_graph(dag):
            errors.append("dag_edges: graph contains a cycle; "
                          "nx.is_d_separator requires a DAG")
        dag_nodes = set(dag.nodes)
        if spec.outcome in cols and spec.outcome not in dag_nodes:
            errors.append(f"dag_edges: outcome '{spec.outcome}' is not "
                          f"a node in the DAG")

    if spec.dsep_threshold <= 0:
        errors.append(f"dsep_threshold must be positive, got {spec.dsep_threshold}")

    if not spec.estimation_variants:
        errors.append("estimation_variants must not be empty "
                      "(at least one variant is required)")

    variant_ids: list[str] = []
    for v in spec.estimation_variants:
        variant_ids.append(v.id)
        _require_col(v.treatment_column, f"estimation_variant '{v.id}' treatment_column")
        if v.treatment_column in cols:
            if v.treatment_form == TreatmentForm.CONTINUOUS:
                _require_numeric(v.treatment_column,
                                 f"estimation_variant '{v.id}' treatment_column (CONTINUOUS)")
            nan_count = int(data[v.treatment_column].isna().sum())
            if nan_count > 0:
                errors.append(f"estimation_variant '{v.id}': treatment_column "
                              f"'{v.treatment_column}' contains {nan_count} NaN value(s)")
            if data[v.treatment_column].nunique(dropna=True) < 2:
                errors.append(f"estimation_variant '{v.id}': treatment_column "
                              f"'{v.treatment_column}' has fewer than 2 unique values")
        for c in v.w_columns:
            _require_col(c, f"estimation_variant '{v.id}' w_columns")
        if v.treatment_form == TreatmentForm.BINARY_THRESHOLD:
            if v.threshold_value is None:
                errors.append(f"estimation_variant '{v.id}': BINARY_THRESHOLD "
                              f"requires a numeric threshold_value")
            elif not isinstance(v.threshold_value, (int, float)):
                errors.append(f"estimation_variant '{v.id}': threshold_value must be "
                              f"numeric, got {type(v.threshold_value).__name__} "
                              f"'{v.threshold_value}'")
        if v.filter:
            _validate_filter(v.filter, f"estimation_variant '{v.id}' filter")

    seen: set[str] = set()
    for vid in variant_ids:
        if vid in seen:
            errors.append(f"duplicate estimation_variant id: '{vid}'")
        seen.add(vid)

    for m in spec.mediators_excluded:
        _require_col(m.column, "mediators_excluded")
        if m.direct_effect_variant_id not in seen:
            errors.append(f"mediators_excluded '{m.column}': "
                          f"direct_effect_variant_id '{m.direct_effect_variant_id}' "
                          f"does not match any estimation variant")

    if spec.mediation:
        for med in spec.mediation:
            _require_col(med.mediator, f"mediation '{med.mediator}'")
            _require_numeric(med.mediator, f"mediation '{med.mediator}'")
            if med.total_variant_id not in seen:
                errors.append(f"mediation '{med.mediator}': total_variant_id "
                              f"'{med.total_variant_id}' not found")
            if med.direct_variant_id not in seen:
                errors.append(f"mediation '{med.mediator}': direct_variant_id "
                              f"'{med.direct_variant_id}' not found")

    for cfg in spec.grf_configs:
        for c in cfg.modifier_columns:
            _require_col(c, f"grf_config '{cfg.id}' modifier_columns")
        for col in cfg.slicing:
            _require_col(col, f"grf_config '{cfg.id}' slicing")
            if cfg.slicing[col] not in ("unique", "quartile"):
                errors.append(f"grf_config '{cfg.id}' slicing['{col}']: "
                              f"method must be 'unique' or 'quartile', "
                              f"got '{cfg.slicing[col]}'")

    g = spec.gates
    if not (0 <= g.nuisance_r2.outcome_abort <= 1):
        errors.append(f"gates.nuisance_r2.outcome_abort must be in [0,1], "
                      f"got {g.nuisance_r2.outcome_abort}")
    if not (0 <= g.nuisance_r2.outcome_flag <= 1):
        errors.append(f"gates.nuisance_r2.outcome_flag must be in [0,1], "
                      f"got {g.nuisance_r2.outcome_flag}")
    if not (0 <= g.nuisance_r2.treatment_abort <= 1):
        errors.append(f"gates.nuisance_r2.treatment_abort must be in [0,1], "
                      f"got {g.nuisance_r2.treatment_abort}")
    if not (0 <= g.nuisance_r2.treatment_flag <= 1):
        errors.append(f"gates.nuisance_r2.treatment_flag must be in [0,1], "
                      f"got {g.nuisance_r2.treatment_flag}")
    if not (0 <= g.nuisance_r2.treatment_structural_max_r2 <= 1):
        errors.append(f"gates.nuisance_r2.treatment_structural_max_r2 must be "
                      f"in [0,1], got {g.nuisance_r2.treatment_structural_max_r2}")
    if g.sanity.expected_direction not in (-1, 1):
        errors.append(f"gates.sanity.expected_direction must be -1 or 1, "
                      f"got {g.sanity.expected_direction}")

    for drop in spec.sensitivity.confounder_drops:
        _require_col(drop.column, "sensitivity.confounder_drops")
        if drop.deviation_threshold_pct <= 0:
            errors.append(f"sensitivity.confounder_drops '{drop.column}': "
                          f"deviation_threshold_pct must be positive, "
                          f"got {drop.deviation_threshold_pct}")
    for add in spec.sensitivity.confounder_adds:
        _require_col(add.column, "sensitivity.confounder_adds")
    for tv in spec.sensitivity.threshold_variants:
        if not isinstance(tv.threshold, (int, float)):
            errors.append(f"sensitivity.threshold_variants: threshold must be "
                          f"numeric, got '{tv.threshold}'")
    for mv in spec.sensitivity.model_variants:
        if mv.primary_variant_id not in seen:
            errors.append(f"sensitivity.model_variants: primary_variant_id "
                          f"'{mv.primary_variant_id}' not found")

    valid_period_freqs = {"D", "W", "M", "Q", "Y"}
    for sb in spec.structural_breaks:
        _require_col(sb.entity_column, f"structural_break '{sb.id}' entity_column")
        _require_col(sb.temporal_column, f"structural_break '{sb.id}' temporal_column")
        if sb.pelt_penalty <= 0:
            errors.append(f"structural_break '{sb.id}': pelt_penalty must be "
                          f"positive, got {sb.pelt_penalty}")
        if sb.min_obs_per_period <= 0:
            errors.append(f"structural_break '{sb.id}': min_obs_per_period must "
                          f"be positive, got {sb.min_obs_per_period}")
        grain_char = sb.temporal_grain[0].upper() if sb.temporal_grain else ""
        if grain_char not in valid_period_freqs:
            errors.append(f"structural_break '{sb.id}': temporal_grain "
                          f"'{sb.temporal_grain}' is not a valid pandas period "
                          f"frequency (expected one starting with D/W/M/Q/Y)")

    for ac in spec.residual_checks.autocorrelation:
        _require_col(ac.temporal_column, "residual_checks.autocorrelation temporal_column")
        if not ac.lags:
            errors.append("residual_checks.autocorrelation: lags must not be empty")
        else:
            if any(lag <= 0 for lag in ac.lags):
                errors.append(f"residual_checks.autocorrelation: all lags must "
                              f"be positive integers, got {ac.lags}")
            max_lag = max(ac.lags)
            if max_lag >= nrows:
                errors.append(f"residual_checks.autocorrelation: max lag "
                              f"{max_lag} must be < data length {nrows} "
                              f"(acorr_ljungbox shape mismatch)")
        if ac.threshold <= 0:
            errors.append(f"residual_checks.autocorrelation: threshold must "
                          f"be positive, got {ac.threshold}")
    if spec.residual_checks.field_correlation.threshold <= 0:
        errors.append("residual_checks.field_correlation.threshold must be "
                      f"positive, got {spec.residual_checks.field_correlation.threshold}")
    for c in spec.residual_checks.field_correlation.check_columns:
        _require_col(c, "residual_checks.field_correlation.check_columns")
    if spec.residual_checks.auto_correction.max_iterations <= 0:
        errors.append("residual_checks.auto_correction.max_iterations must be "
                      f"positive, got {spec.residual_checks.auto_correction.max_iterations}")
    for mc in spec.residual_checks.metadata_correlation:
        _require_col(mc.column, "residual_checks.metadata_correlation")

    for ov in spec.range_checks.overlap:
        if ov.variant_id not in seen:
            errors.append(f"range_checks.overlap: variant_id "
                          f"'{ov.variant_id}' not found")
        if not (0 < ov.threshold <= 1):
            errors.append(f"range_checks.overlap '{ov.variant_id}': "
                          f"threshold must be in (0,1], got {ov.threshold}")
        from dto.causal_verification_request import OverlapStrategy
        if ov.response_strategy == OverlapStrategy.TRIM:
            if ov.trim_bounds is None or len(ov.trim_bounds) != 2:
                errors.append(f"range_checks.overlap '{ov.variant_id}': "
                              f"TRIM strategy requires trim_bounds with "
                              f"exactly 2 values [low, high]")
            elif ov.trim_bounds[0] >= ov.trim_bounds[1]:
                errors.append(f"range_checks.overlap '{ov.variant_id}': "
                              f"trim_bounds[0] must be < trim_bounds[1], "
                              f"got {ov.trim_bounds}")
    for vc in spec.range_checks.variance:
        _require_col(vc.column, "range_checks.variance")

    for uc in spec.unmeasured_confounding:
        if uc.variant_id not in seen:
            errors.append(f"unmeasured_confounding: variant_id "
                          f"'{uc.variant_id}' not found")

    if spec.positivity_check:
        pc = spec.positivity_check
        if not pc.treatment_hierarchy:
            errors.append("positivity_check.treatment_hierarchy must not be empty")
        else:
            for lvl in pc.treatment_hierarchy:
                _require_col(lvl, "positivity_check.treatment_hierarchy")
            if spec.treatment not in pc.treatment_hierarchy:
                errors.append(
                    f"positivity_check.treatment_hierarchy must contain the "
                    f"treatment column '{spec.treatment}' — hierarchy levels "
                    f"must be coarsenings of the treatment, not unrelated "
                    f"columns. Got: {pc.treatment_hierarchy}")

    if spec.externalization:
        for ab in spec.externalization.allocation_bias:
            _require_col(ab.treatment_column, "externalization.allocation_bias treatment_column")
            _require_col(ab.grouping_column, "externalization.allocation_bias grouping_column")
        from service.tier_ordering import parse_ordering
        for dr in spec.externalization.domain_rankings:
            if not dr.ordering or not dr.ordering.strip():
                errors.append(f"externalization.domain_rankings '{dr.source}': "
                              f"ordering is empty")
            else:
                try:
                    parse_ordering(dr.ordering)
                except ValueError as e:
                    errors.append(f"externalization.domain_rankings '{dr.source}': "
                                  f"invalid ordering notation: {e}")
            if not isinstance(dr.expected_concordance, (int, float)):
                errors.append(f"externalization.domain_rankings '{dr.source}': "
                              f"expected_concordance must be numeric "
                              f"(fraction of pairwise assertions, 0.0-1.0), "
                              f"got {type(dr.expected_concordance).__name__}")
            elif not (0.0 <= dr.expected_concordance <= 1.0):
                errors.append(f"externalization.domain_rankings '{dr.source}': "
                              f"expected_concordance must be in [0, 1], "
                              f"got {dr.expected_concordance}")

    if errors:
        raise ValueError(
            f"Pipeline spec validation failed ({len(errors)} error(s)):\n"
            + "\n".join(f"  • {e}" for e in errors)
        )
