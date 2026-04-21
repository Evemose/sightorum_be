import numpy as np
import os
import pandas as pd
import pytest
import sys
from dataclasses import dataclass, field, replace as dc_replace
from typing import Any, Optional

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from service.pipeline_dataframe import PipelineDataFrame
from dto.causal_verification_request import (
    AllocationBias,
    AutoCorrectionConfig,
    AutocorrelationCheck,
    CausalVerificationRequest,
    ConfounderAdd,
    ConfounderDrop,
    DomainRanking,
    EstimationVariant,
    ExternalizationConfig,
    FieldCorrelationCheck,
    GrfConfig,
    MediationConfig,
    MediatorExclusion,
    MetadataCorrelation,
    ModelVariant,
    NuisanceR2Gates,
    OverlapCheck,
    OverlapStrategy,
    PlaceboGates,
    PositivityCheck,
    QualityGates,
    RangeChecks,
    RefutationConfig,
    RefutationType,
    ResidualChecks,
    SanityGates,
    SensitivityConfig,
    StructuralBreakConfig,
    ThresholdVariant,
    TreatmentForm,
    UnmeasuredConfoundingConfig,
    UnmeasuredMethod,
    VarianceCheck,
    VariantFilter,
    FilterOperator,
    VifConfig,
)
from dto.requests import SQLDatasourceConfig


def make_pipeline_df(n=200, treatment_col="treatment", outcome_col="outcome",
                     confounders=None, cat_treatment=False,
                     extra_cols=None, seed=42):
    rng = np.random.default_rng(seed)
    confounders = confounders or ["conf_a", "conf_b"]
    data = {
        outcome_col: rng.normal(50, 10, n),
    }
    if cat_treatment:
        data[treatment_col] = rng.choice(["low", "mid", "high"], n)
    else:
        data[treatment_col] = rng.normal(100, 20, n)

    for c in confounders:
        data[c] = rng.choice(["A", "B", "C"], n)

    if extra_cols:
        for col_name, col_data in extra_cols.items():
            if callable(col_data):
                data[col_name] = col_data(rng, n)
            else:
                data[col_name] = col_data

    df = pd.DataFrame(data)
    return PipelineDataFrame.from_dataframe(df)


def make_sql_datasource():
    return SQLDatasourceConfig(
        sql="SELECT 1",
        bind_variables={},
    )


def make_causal_df(n=5000, true_ate=0.5, confounder_strength=1.5, noise_sd=2.0, seed=42):
    """Generate data with known causal structure for numerical verification.

    DGP: conf ~ N(0,1), treatment = 2*conf + N(0,1), outcome = true_ate*treatment + confounder_strength*conf + N(0, noise_sd)
    True ATE = true_ate. DML should recover this within CI.
    """
    rng = np.random.default_rng(seed)
    conf = rng.normal(0, 1, n)
    treatment = 2 * conf + rng.normal(0, 1, n)
    outcome = true_ate * treatment + confounder_strength * conf + rng.normal(0, noise_sd, n)
    df = pd.DataFrame({
        "treatment": treatment,
        "outcome": outcome,
        "conf": conf,
        "noise": rng.normal(0, 1, n),
    })
    return PipelineDataFrame.from_dataframe(df), true_ate


def make_binary_causal_df(n=5000, true_ate=5.0, seed=42):
    """Generate data with binary treatment and known effect.

    DGP: conf ~ N(0,1), treatment ∈ {low, high} with P(high) ~ 0.5,
    outcome = true_ate * I(high) + conf + N(0,1)
    """
    rng = np.random.default_rng(seed)
    conf = rng.normal(0, 1, n)
    treat = rng.choice(["low", "high"], n)
    outcome = np.where(treat == "high", true_ate, 0.0) + conf + rng.normal(0, 1, n)
    df = pd.DataFrame({"treatment": treat, "outcome": outcome, "conf": conf})
    return PipelineDataFrame.from_dataframe(df), true_ate


def make_h3_data(n=10_000, seed=42):
    """Synthetic cold-chain data mimicking H3 nodeRefrigHealthPct→excursionFlag.

    Reproduces:
    - nodeRefrigHealthPct concentrated at 100%, ~2% tail below 70%
    - 28 nodes, 7 with ZERO health variation (always 100%)
    - excursionFlag binary, ~6.5% base rate, ~49% when health<70
    - nodeId → region → climateZone hierarchy
    - preDepartureTempC as mediator (corr with health ~ -0.15)
    - ~350 rows/node, ~200 below-70 obs
    """
    rng = np.random.default_rng(seed)
    nodes = [f"NOD_{i:03d}" for i in range(28)]
    regions = {f"NOD_{i:03d}": f"region_{i // 4}" for i in range(28)}
    climates = {f"region_{i}": ["humid_continental", "oceanic", "humid_subtropical",
                                "hot_desert", "semi_arid", "oceanic", "humid_continental"][i]
                for i in range(7)}

    node_col = rng.choice(nodes, n)
    region_col = np.array([regions[nd] for nd in node_col])
    climate_col = np.array([climates[regions[nd]] for nd in node_col])

    health = np.full(n, 100.0)
    zero_variation_nodes = set(nodes[:7])
    for i in range(n):
        if node_col[i] not in zero_variation_nodes:
            if rng.random() < 0.02:
                health[i] = rng.uniform(30, 69)
            elif rng.random() < 0.05:
                health[i] = rng.uniform(70, 95)

    pre_dep_temp = -0.15 * health + rng.normal(5, 2, n)
    excursion_prob = np.where(health < 70, 0.49, 0.065)
    excursion = rng.binomial(1, excursion_prob)

    ambient = rng.normal(20, 10, n)
    has_redundancy = rng.choice(["True", "False"], n)
    vehicle = rng.choice(["Ford_E-450", "Ford_E-Transit", "Freightliner_M2_112"], n)
    refrig_age = rng.integers(8, 100, n).astype(float)
    power_status = np.where(rng.random(n) < 0.0005, "outage", "normal")
    dispatch_year = rng.choice([2016, 2017, 2018, 2019, 2020, 2021, 2022, 2023, 2024, 2025], n)
    dispatch_month = rng.integers(1, 13, n)
    route_hours = rng.uniform(2, 12, n)
    stop_seq = rng.integers(1, 8, n)

    df = pd.DataFrame({
        "nodeRefrigHealthPct": health,
        "excursionFlag": excursion,
        "nodeId": node_col,
        "region": region_col,
        "climateZone": climate_col,
        "preDepartureTempC": pre_dep_temp,
        "ambientTempAtDispatchC": ambient,
        "hasRedundancy": has_redundancy,
        "vehicleMakeModel": vehicle,
        "refrigSystemAgeMonthsAtStart": refrig_age,
        "nodePowerStatus": power_status,
        "dispatchYear": dispatch_year,
        "dispatchMonth": dispatch_month,
        "routeTotalDriveHours": route_hours,
        "stopSequence": stop_seq,
    })
    return PipelineDataFrame.from_dataframe(df)


H3_CONFOUNDERS = [
    "ambientTempAtDispatchC", "climateZone", "region", "hasRedundancy",
    "vehicleMakeModel", "refrigSystemAgeMonthsAtStart",
    "dispatchYear", "dispatchMonth", "routeTotalDriveHours", "stopSequence",
]

H3_DAG_EDGES = (
    "nodeRefrigHealthPct -> preDepartureTempC; "
    "nodeRefrigHealthPct -> excursionFlag; "
    "preDepartureTempC -> excursionFlag; "
    "nodePowerStatus -> nodeRefrigHealthPct; "
    "ambientTempAtDispatchC -> nodeRefrigHealthPct; "
    "ambientTempAtDispatchC -> excursionFlag; "
    "climateZone -> ambientTempAtDispatchC; "
    "climateZone -> excursionFlag; "
    "hasRedundancy -> nodeRefrigHealthPct; "
    "refrigSystemAgeMonthsAtStart -> nodeRefrigHealthPct; "
    "nodeId -> nodeRefrigHealthPct; "
    "nodeId -> climateZone; "
    "dispatchYear -> nodeRefrigHealthPct"
)


def make_h3_spec(confounders=None, include_node_id=True, positivity=True,
                 positivity_hierarchy=None):
    confs = list(confounders or H3_CONFOUNDERS)
    if include_node_id and "nodeId" not in confs:
        confs.insert(0, "nodeId")
    hierarchy = positivity_hierarchy or ["nodeRefrigHealthPct"]
    return make_spec(
        treatment="nodeRefrigHealthPct",
        outcome="excursionFlag",
        treatment_form=TreatmentForm.CONTINUOUS,
        confounders=confs,
        dag_edges=H3_DAG_EDGES,
        estimation_variants=[
            EstimationVariant(
                id="continuous_total",
                treatment_column="nodeRefrigHealthPct",
                treatment_form=TreatmentForm.CONTINUOUS,
                model_type="LinearDML", w_columns=confs,
            ),
            EstimationVariant(
                id="binary_70",
                treatment_column="nodeRefrigHealthPct",
                treatment_form=TreatmentForm.BINARY_THRESHOLD,
                model_type="LinearDML", w_columns=confs,
                threshold_value=70.0,
            ),
        ],
        positivity_check=PositivityCheck(
            treatment_hierarchy=hierarchy,
            relative_threshold=0.3,
        ) if positivity else None,
        sensitivity=SensitivityConfig(
            confounder_drops=[
                ConfounderDrop(column="ambientTempAtDispatchC", deviation_threshold_pct=25.0),
            ],
            confounder_adds=[
                ConfounderAdd(column="nodePowerStatus", reasoning="collider test"),
            ],
            threshold_variants=[
                ThresholdVariant(threshold=50.0, expected_n_treated=50, expected_n_control=9950),
                ThresholdVariant(threshold=70.0, expected_n_treated=60, expected_n_control=9940),
                ThresholdVariant(threshold=90.0, expected_n_treated=100, expected_n_control=9900),
            ],
            model_variants=[
                ModelVariant(primary_variant_id="continuous_total",
                             alternative_model_type="NonParamDML"),
            ],
        ),
        residual_checks=ResidualChecks(
            autocorrelation=[],
            field_correlation=FieldCorrelationCheck(
                threshold=0.03,
                check_columns=["preDepartureTempC", "ambientTempAtDispatchC",
                               "nodeRefrigHealthPct", "climateZone", "region",
                               "nodePowerStatus"],
            ),
            auto_correction=AutoCorrectionConfig(max_iterations=5, stop_criterion_ci_pct=5.0),
            metadata_correlation=[],
        ),
        externalization=ExternalizationConfig(
            domain_rankings=[
                DomainRanking(ordering="(50) > (60) > (70) > (80) > (90)",
                              source="H3_hypothesis", scope="all",
                              expected_concordance=0.75),
            ],
            allocation_bias=[
                AllocationBias(treatment_column="nodeRefrigHealthPct",
                               grouping_column="nodeId", flag_threshold=0.1),
                AllocationBias(treatment_column="nodeRefrigHealthPct",
                               grouping_column="region", flag_threshold=0.2),
            ],
        ),
        mediation=[
            MediationConfig(mediator="preDepartureTempC",
                            pathway="nodeRefrigHealthPct→preDepartureTempC→excursionFlag",
                            total_variant_id="continuous_total",
                            direct_variant_id="continuous_total"),
        ],
    )


def make_spec(treatment="treatment", outcome="outcome",
              treatment_form=TreatmentForm.CONTINUOUS,
              confounders=None, dag_edges=None,
              estimation_variants=None,
              positivity_check=None,
              mediation=None,
              grf_configs=None,
              refutations=None,
              sensitivity=None,
              structural_breaks=None,
              residual_checks=None,
              range_checks=None,
              unmeasured_confounding=None,
              externalization=None,
              original_treatment=None,
              ):
    confounders = confounders or ["conf_a", "conf_b"]
    dag_edges = dag_edges or f"{treatment} -> {outcome}; " + "; ".join(
        f"{c} -> {outcome}; {c} -> {treatment}" for c in confounders
    )
    estimation_variants = estimation_variants or [
        EstimationVariant(
            id="v1", treatment_column=treatment,
            treatment_form=treatment_form,
            model_type="linear_dml", w_columns=confounders,
        )
    ]
    sensitivity = sensitivity or SensitivityConfig(
        confounder_drops=[],
        confounder_adds=[],
        threshold_variants=[],
        model_variants=[],
    )
    residual_checks = residual_checks or ResidualChecks(
        autocorrelation=[],
        field_correlation=FieldCorrelationCheck(threshold=0.1, check_columns=[]),
        auto_correction=AutoCorrectionConfig(max_iterations=3, stop_criterion_ci_pct=5.0),
        metadata_correlation=[],
    )
    range_checks = range_checks or RangeChecks(
        vif=VifConfig(threshold=10.0, drop_pairs=[]),
        overlap=[],
        variance=[],
    )
    externalization = externalization or ExternalizationConfig(
        domain_rankings=[], allocation_bias=[],
    )

    return CausalVerificationRequest(
        hypothesis_id="test_hyp",
        treatment=treatment,
        outcome=outcome,
        treatment_form=treatment_form,
        datasource=make_sql_datasource(),
        expected_row_count=0,
        strip_columns=[],
        dag_edges=dag_edges,
        dsep_threshold=0.05,
        adjustment_set=confounders,
        mediators_excluded=[],
        positivity_check=positivity_check,
        estimation_variants=estimation_variants,
        gates=QualityGates(
            nuisance_r2=NuisanceR2Gates(
                outcome_abort=0.0, outcome_flag=0.1,
                treatment_abort=0.0, treatment_flag=0.1,
                treatment_structural_max_r2=0.95,
            ),
            sanity=SanityGates(expected_direction=1, abort_magnitude=100.0, flag_magnitude=50.0),
            placebo=PlaceboGates(flag_ratio=0.5),
        ),
        mediation=mediation,
        grf_configs=grf_configs or [],
        refutations=refutations or [],
        sensitivity=sensitivity,
        structural_breaks=structural_breaks or [],
        residual_checks=residual_checks,
        range_checks=range_checks,
        unmeasured_confounding=unmeasured_confounding or [],
        externalization=externalization,
        original_treatment=original_treatment,
    )
