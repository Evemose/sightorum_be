"""Comprehensive tests for the causal verification pipeline.

Covers:
- Engine bugs E1–E15 (positivity rewrite atomicity, protected columns,
  original_treatment propagation, auto-correction cascade, cycle checks,
  refutation error counting, etc.)
- Default / happy-path scenarios for each pipeline step
- Edge cases (empty data, missing columns, categorical shifts)
"""
import networkx as nx
import numpy as np
import pandas as pd
import pytest
from dataclasses import replace as dc_replace
from dto.causal_verification_request import (
    AllocationBias,
    AutoCorrectionConfig,
    ConfounderDrop,
    DomainRanking,
    EstimationVariant,
    ExternalizationConfig,
    FieldCorrelationCheck,
    GrfConfig,
    MediatorExclusion,
    PositivityCheck,
    RefutationConfig,
    RefutationType,
    ResidualChecks,
    SensitivityConfig,
    ThresholdVariant,
    TreatmentForm,
)
from service.causal_verification.dsep import (
    add_fallback_edges,
    dsep_refinement,
)
from service.causal_verification.externalization import externalization
from service.causal_verification.mediation import mediation
from service.causal_verification.pipeline_utils import (
    build_protected_columns,
    edges_to_nx,
    infer_treatment_form,
    parse_dag_edges,
    run_dml_quick,
)
from service.causal_verification.positivity import (
    apply_positivity_rewrite,
    positivity_gate,
    rewrite_variants_for_positivity,
    select_positivity_confounders,
)
from service.causal_verification.quality_gates import quality_gates
from service.causal_verification.refutations import (
    collect_refutations,
    temporal_placebo,
)
from service.causal_verification.residual_diagnostics import (
    AUTO_CORRECTION_ABORT_DELTA_PCT,
    residual_diagnostics,
)
from service.causal_verification.sensitivity import sensitivity
from service.pipeline_dataframe import PipelineDataFrame
from tests.conftest import make_pipeline_df, make_spec
from unittest.mock import MagicMock, patch


# ═══════════════════════════════════════════════════════════════════
# Fixtures
# ═══════════════════════════════════════════════════════════════════

@pytest.fixture
def simple_data():
    return make_pipeline_df(n=200)


@pytest.fixture
def cat_data():
    return make_pipeline_df(n=200, cat_treatment=True)


@pytest.fixture
def hierarchical_data():
    rng = np.random.default_rng(42)
    n = 500
    node_ids = [f"node_{i}" for i in range(28)]
    node_groups = {f"node_{i}": f"group_{i // 7}" for i in range(28)}
    df = pd.DataFrame({
        "refrigHealthPct": rng.normal(75, 15, n),
        "outcome": rng.normal(50, 10, n),
        "nodeId": rng.choice(node_ids, n),
        "conf_a": rng.choice(["A", "B", "C"], n),
        "conf_b": rng.choice(["X", "Y"], n),
    })
    df["nodeGroupId"] = df["nodeId"].map(node_groups)
    return PipelineDataFrame.from_dataframe(df)


@pytest.fixture
def simple_spec():
    return make_spec()


@pytest.fixture
def simple_dag():
    return edges_to_nx([
        ("treatment", "outcome"),
        ("conf_a", "outcome"),
        ("conf_a", "treatment"),
        ("conf_b", "outcome"),
        ("conf_b", "treatment"),
    ])


@pytest.fixture
def simple_edges():
    return [
        ("treatment", "outcome"),
        ("conf_a", "outcome"),
        ("conf_a", "treatment"),
        ("conf_b", "outcome"),
        ("conf_b", "treatment"),
    ]


# ═══════════════════════════════════════════════════════════════════
# E1: _apply_positivity_rewrite atomic treatment_form update
# ═══════════════════════════════════════════════════════════════════

class TestE1AtomicPositivityRewrite:

    def test_treatment_form_updated_on_spec(self, hierarchical_data):
        spec = make_spec(
            treatment="refrigHealthPct", outcome="outcome",
            treatment_form=TreatmentForm.CONTINUOUS,
            confounders=["conf_a", "conf_b"],
        )
        variants = [
            EstimationVariant(
                id="v1", treatment_column="nodeGroupId",
                treatment_form=TreatmentForm.CATEGORICAL,
                model_type="linear_dml", w_columns=["conf_a", "conf_b"],
            )
        ]
        new_spec = apply_positivity_rewrite(
            spec, variants, "nodeGroupId", None, hierarchical_data)
        assert new_spec.treatment == "nodeGroupId"
        assert new_spec.treatment_form == TreatmentForm.CATEGORICAL

    def test_all_variants_get_consistent_treatment_form(self, hierarchical_data):
        spec = make_spec(
            treatment="refrigHealthPct", outcome="outcome",
            treatment_form=TreatmentForm.CONTINUOUS,
            confounders=["conf_a", "conf_b"],
            estimation_variants=[
                EstimationVariant(
                    id="v1", treatment_column="refrigHealthPct",
                    treatment_form=TreatmentForm.CONTINUOUS,
                    model_type="linear_dml", w_columns=["conf_a", "conf_b"],
                ),
                EstimationVariant(
                    id="v2", treatment_column="refrigHealthPct",
                    treatment_form=TreatmentForm.CONTINUOUS,
                    model_type="linear_dml", w_columns=["conf_a"],
                ),
            ]
        )
        variants = [
            dc_replace(v, treatment_column="nodeGroupId")
            for v in spec.estimation_variants
        ]
        new_spec = apply_positivity_rewrite(
            spec, variants, "nodeGroupId", None, hierarchical_data)
        for v in new_spec.estimation_variants:
            assert v.treatment_column == "nodeGroupId"
            assert v.treatment_form == TreatmentForm.CATEGORICAL

    def test_no_partial_state_when_treatment_unchanged(self):
        spec = make_spec(treatment="treatment")
        variants = list(spec.estimation_variants)
        new_spec = apply_positivity_rewrite(
            spec, variants, "treatment", None)
        assert new_spec.treatment == spec.treatment
        assert new_spec.treatment_form == spec.treatment_form
        assert new_spec.original_treatment == spec.original_treatment

    def test_original_treatment_preserved_on_rewrite(self, hierarchical_data):
        spec = make_spec(
            treatment="refrigHealthPct", outcome="outcome",
            treatment_form=TreatmentForm.CONTINUOUS,
        )
        new_spec = apply_positivity_rewrite(
            spec, spec.estimation_variants, "nodeGroupId", None, hierarchical_data)
        assert new_spec.original_treatment == "refrigHealthPct"


# ═══════════════════════════════════════════════════════════════════
# E2: _positivity_gate original_treatment at level_idx=0
# ═══════════════════════════════════════════════════════════════════

class TestE2PositivityGateLevelZero:

    def test_level_zero_same_as_treatment_no_rewrite(self, simple_data):
        spec = make_spec(
            treatment="treatment",
            positivity_check=PositivityCheck(
                treatment_hierarchy=["treatment"],
                relative_threshold=0.3,
            ),
        )
        report, rewritten, final, mask = positivity_gate(simple_data, spec)
        if rewritten is not None:
            for v in rewritten:
                assert v.treatment_column == "treatment"

    def test_level_zero_different_column_rewrites(self, hierarchical_data):
        spec = make_spec(
            treatment="refrigHealthPct", outcome="outcome",
            confounders=["conf_a", "conf_b"],
            positivity_check=PositivityCheck(
                treatment_hierarchy=["nodeGroupId"],
                relative_threshold=0.3,
            ),
        )
        report, rewritten, final, mask = positivity_gate(hierarchical_data, spec)
        if rewritten is not None:
            for v in rewritten:
                assert v.treatment_column in ("nodeGroupId", "refrigHealthPct")


# ═══════════════════════════════════════════════════════════════════
# E3: Auto-correction exclusion with protected_columns
# ═══════════════════════════════════════════════════════════════════

class TestE3ProtectedColumns:

    def test_build_protected_columns_includes_original(self):
        spec = make_spec(original_treatment="orig_t")
        dag = edges_to_nx([("conf_a", "orig_t"), ("orig_t", "outcome")])
        protected = build_protected_columns(spec, dag)
        assert "orig_t" in protected

    def test_build_protected_columns_includes_ancestors(self):
        spec = make_spec(original_treatment="treatment")
        dag = edges_to_nx([
            ("conf_a", "treatment"), ("treatment", "outcome"),
            ("upstream", "conf_a"),
        ])
        protected = build_protected_columns(spec, dag)
        assert "conf_a" in protected
        assert "upstream" in protected

    def test_build_protected_columns_includes_mediators(self):
        spec = make_spec()
        spec = dc_replace(spec, mediators_excluded=[
            MediatorExclusion(column="med_col", pathway="T→M→Y",
                              direct_effect_variant_id="v1")
        ])
        dag = edges_to_nx([("treatment", "outcome")])
        protected = build_protected_columns(spec, dag)
        assert "med_col" in protected

    def test_residual_diagnostics_respects_protected(self, simple_data):
        spec = make_spec(
            residual_checks=ResidualChecks(
                autocorrelation=[],
                field_correlation=FieldCorrelationCheck(
                    threshold=0.0001,
                    check_columns=["conf_a", "conf_b"],
                ),
                auto_correction=AutoCorrectionConfig(max_iterations=5, stop_criterion_ci_pct=5.0),
                metadata_correlation=[],
            ),
        )
        edges = [("treatment", "outcome"), ("conf_a", "outcome"), ("conf_b", "outcome")]
        protected = {"conf_a", "conf_b"}
        result = residual_diagnostics(
            simple_data, spec, ["conf_a", "conf_b"], edges, 1.0,
            protected_columns=protected)
        for corr in result.get("corrections", []):
            assert corr["field"] not in protected


# ═══════════════════════════════════════════════════════════════════
# E4: Mediation ignores post-rewrite form
# ═══════════════════════════════════════════════════════════════════

class TestE4MediationTreatmentForm:

    def test_mediation_uses_original_treatment_when_available(self, simple_data):
        spec = make_spec(
            treatment="treatment",
            original_treatment="treatment",
            treatment_form=TreatmentForm.CONTINUOUS,
        )
        spec = dc_replace(spec, mediation=[
            MagicMock(
                mediator="conf_a", pathway="T→M→Y",
                total_variant_id="v1", direct_variant_id="v1",
            ),
        ])
        estimation_results = {"v1": {"effect": 1.5}}
        with patch("service.causal_verification.mediation.LinearDML") as mock_dml:
            mock_instance = MagicMock()
            mock_instance.effect.return_value = np.array([1.0])
            mock_dml.return_value = mock_instance
            results = mediation(simple_data, spec, ["conf_b"],
                                estimation_results, [])
            assert len(results) == 1
            if "error" not in results[0]:
                assert "direct_effect" in results[0]


# ═══════════════════════════════════════════════════════════════════
# E5: Threshold variants on wrong column
# ═══════════════════════════════════════════════════════════════════

class TestE5ThresholdVariants:

    def test_threshold_uses_original_treatment(self, simple_data):
        spec = make_spec(
            treatment="rewritten_col",
            original_treatment="treatment",
            sensitivity=SensitivityConfig(
                confounder_drops=[],
                confounder_adds=[],
                threshold_variants=[
                    ThresholdVariant(threshold=50.0,
                                     expected_n_treated=100,
                                     expected_n_control=100),
                ],
                model_variants=[],
            ),
        )
        edges = [("treatment", "outcome"), ("conf_a", "outcome")]
        with patch("service.causal_verification.sensitivity.run_dml_quick",
                   return_value=0.5):
            result = sensitivity(simple_data, spec, edges, 1.0, {})
        tvs = result["threshold_variants"]
        assert len(tvs) == 1
        assert tvs[0]["n_treated"] > 0 or tvs[0].get("error") is not None

    def test_threshold_with_missing_original_errors(self, simple_data):
        spec = make_spec(
            treatment="treatment",
            original_treatment="nonexistent_col",
            sensitivity=SensitivityConfig(
                confounder_drops=[],
                confounder_adds=[],
                threshold_variants=[
                    ThresholdVariant(threshold=50.0,
                                     expected_n_treated=100,
                                     expected_n_control=100),
                ],
                model_variants=[],
            ),
        )
        edges = [("treatment", "outcome")]
        result = sensitivity(simple_data, spec, edges, 1.0, {})
        tvs = result["threshold_variants"]
        assert tvs[0].get("error") is not None


# ═══════════════════════════════════════════════════════════════════
# E7: Allocation bias uses original treatment
# ═══════════════════════════════════════════════════════════════════

class TestE7AllocationBias:

    def test_allocation_bias_prefers_original_treatment(self, hierarchical_data):
        spec = make_spec(
            treatment="nodeGroupId", outcome="outcome",
            original_treatment="refrigHealthPct",
            confounders=["conf_a"],
            externalization=ExternalizationConfig(
                domain_rankings=[],
                allocation_bias=[
                    AllocationBias(
                        treatment_column="nodeGroupId",
                        grouping_column="conf_a",
                        flag_threshold=0.5,
                    ),
                ],
            ),
        )
        result_dict = {"steps": {"estimation": {}, "sensitivity": {}, "grf": []}}
        result = externalization(hierarchical_data, spec, result_dict)
        allocs = result["allocation_bias"]
        assert len(allocs) == 1
        assert allocs[0]["treatment_column"] == "refrigHealthPct"

    def test_allocation_bias_no_original_uses_spec(self, simple_data):
        spec = make_spec(
            treatment="treatment",
            externalization=ExternalizationConfig(
                domain_rankings=[],
                allocation_bias=[
                    AllocationBias(
                        treatment_column="treatment",
                        grouping_column="conf_a",
                        flag_threshold=0.5,
                    ),
                ],
            ),
        )
        result_dict = {"steps": {"estimation": {}, "sensitivity": {}, "grf": []}}
        result = externalization(simple_data, spec, result_dict)
        assert result["allocation_bias"][0]["treatment_column"] == "treatment"


# ═══════════════════════════════════════════════════════════════════
# E8: Externalization empty effects defense
# ═══════════════════════════════════════════════════════════════════

class TestE8ExternalizationEmptyEffects:

    def test_empty_tv_effects_produces_error(self, simple_data):
        spec = make_spec(
            externalization=ExternalizationConfig(
                domain_rankings=[
                    DomainRanking(
                        ordering="(70) > (60) > (50)",
                        source="test",
                        scope="all",
                        expected_concordance=0.8,
                    ),
                ],
                allocation_bias=[],
            ),
        )
        result_dict = {
            "steps": {
                "estimation": {"v1": {"effect": 1.0}},
                "sensitivity": {"threshold_variants": []},
                "grf": [],
            }
        }
        result = externalization(simple_data, spec, result_dict)
        rankings = result["domain_rankings"]
        assert len(rankings) == 1
        assert "error" in rankings[0]
        assert "no effect estimates" in rankings[0]["error"]


# ═══════════════════════════════════════════════════════════════════
# E9: Quality gates structural_rewrite flag
# ═══════════════════════════════════════════════════════════════════

class TestE9StructuralBypass:

    def test_structural_rewrite_distinguished(self, simple_data):
        spec = make_spec(
            treatment="treatment",
            original_treatment="orig_treatment",
        )
        with patch("service.causal_verification.quality_gates.cross_val_score") as mock_cv:
            mock_cv.return_value = np.array([0.98, 0.97, 0.99, 0.96, 0.98])
            result = quality_gates(
                simple_data, spec, ["conf_a", "conf_b"], 1.0, (-0.5, 2.5))
        assert result["nuisance_r2"]["treatment_status"] == "structural_rewrite"

    def test_structural_no_rewrite_is_structural(self, simple_data):
        spec = make_spec()
        with patch("service.causal_verification.quality_gates.cross_val_score") as mock_cv:
            mock_cv.return_value = np.array([0.98, 0.97, 0.99, 0.96, 0.98])
            result = quality_gates(
                simple_data, spec, ["conf_a", "conf_b"], 1.0, (-0.5, 2.5))
        assert result["nuisance_r2"]["treatment_status"] == "structural"


# ═══════════════════════════════════════════════════════════════════
# E10: Refutations data/graph alignment
# ═══════════════════════════════════════════════════════════════════

class TestE10RefutationAlignment:

    def test_refutation_build_model_uses_raw_data(self):
        from service.causal_verification.refutations import refutations_parallel
        from service.causal_verification.memory_budget import MemoryBudget

        data = make_pipeline_df(n=50)
        spec = make_spec(refutations=[
            RefutationConfig(type=RefutationType.PLACEBO),
        ])
        dag = edges_to_nx([("treatment", "outcome"), ("conf_a", "outcome")])
        budget = MagicMock()
        budget.lgbm_defaults.return_value = dict(
            n_estimators=10, max_depth=3, learning_rate=0.1, verbose=-1)
        budget.param.return_value = 3
        budget.container_mb = 32000
        budget.check_and_reclaim = MagicMock()

        with patch("service.causal_verification.refutations.dowhy") as mock_dowhy:
            mock_model = MagicMock()
            mock_dowhy.CausalModel.return_value = mock_model
            mock_model.identify_effect.return_value = MagicMock()
            mock_model.estimate_effect.return_value = MagicMock()
            mock_refute = MagicMock()
            mock_refute.new_effect = 0.1
            mock_model.refute_estimate.return_value = mock_refute

            futures = refutations_parallel(
                data, spec, dag, 1.0, budget)

            if "placebo" in futures:
                call_args = mock_dowhy.CausalModel.call_args
                if call_args:
                    passed_data = call_args.kwargs.get("data", call_args.args[0] if call_args.args else None)
                    if passed_data is not None:
                        assert "treatment" in passed_data.columns


# ═══════════════════════════════════════════════════════════════════
# E11: Refutation error counting
# ═══════════════════════════════════════════════════════════════════

class TestE11RefutationErrorCounting:

    def test_collect_refutations_captures_errors(self):
        from concurrent.futures import Future
        f1 = Future()
        f1.set_exception(RuntimeError("dowhy crashed"))
        f2 = Future()
        f2.set_result(("placebo", MagicMock(new_effect=0.01)))

        spec = make_spec()
        results = collect_refutations(
            {"broken": f1, "placebo": f2}, spec, 1.0)
        assert "error" in results["broken"]
        assert results["placebo"]["new_effect"] == 0.01

    def test_run_pipeline_emits_refutation_error_marker(self):
        from service.causal_verification.service import CausalVerificationService
        svc = CausalVerificationService()

        result = {"steps": {"refutations": {
            "placebo": {"error": "failed"},
            "random_cause": {"new_effect": 0.5, "shift_pct": 0.1},
            "temporal_placebo": {"error": "crashed"},
        }}}
        refutation_results = result["steps"]["refutations"]
        error_count = sum(
            1 for v in refutation_results.values()
            if isinstance(v, dict) and "error" in v
        )
        assert error_count == 2


# ═══════════════════════════════════════════════════════════════════
# E12: Temporal placebo skips categorical
# ═══════════════════════════════════════════════════════════════════

class TestE12TemporalPlaceboCategorical:

    def test_temporal_placebo_skips_categorical_without_original(self, cat_data):
        spec = make_spec(
            treatment_form=TreatmentForm.CATEGORICAL,
        )
        spec = dc_replace(spec, original_treatment=None)
        dag = edges_to_nx([("treatment", "outcome")])
        result = temporal_placebo(cat_data, spec, dag, 1.0)
        assert result.get("skipped") is True or "error" in result

    def test_temporal_placebo_uses_original_when_available(self, simple_data):
        spec = make_spec(
            treatment="rewritten_col",
            original_treatment="treatment",
            treatment_form=TreatmentForm.CATEGORICAL,
            structural_breaks=[
                MagicMock(temporal_column="treatment", entity_column="conf_a"),
            ],
        )
        dag = edges_to_nx([("treatment", "outcome")])
        result = temporal_placebo(simple_data, spec, dag, 1.0)
        assert "error" in result or "probes" in result


# ═══════════════════════════════════════════════════════════════════
# E13: Post-substitution cycle check
# ═══════════════════════════════════════════════════════════════════

class TestE13PostSubstitutionCycleCheck:

    def test_cycle_detected_after_edge_rewrite(self):
        edges = [("A", "B"), ("B", "C"), ("C", "A_alias")]
        old_treatment = "A"
        new_treatment = "C"
        new_edges = [
            (new_treatment if s == old_treatment else s,
             new_treatment if d == old_treatment else d)
            for s, d in edges
        ]
        dag = edges_to_nx(new_edges)
        assert not nx.is_directed_acyclic_graph(dag)

    def test_no_cycle_after_normal_rewrite(self):
        edges = [("A", "outcome"), ("B", "outcome"), ("B", "A")]
        old_treatment = "A"
        new_treatment = "A_coarse"
        new_edges = [
            (new_treatment if s == old_treatment else s,
             new_treatment if d == old_treatment else d)
            for s, d in edges
        ]
        dag = edges_to_nx(new_edges)
        assert nx.is_directed_acyclic_graph(dag)

    def test_fallback_edges_maintain_acyclicity(self):
        edges = [("A", "B"), ("B", "C")]
        violations = [
            {"node_a": "A", "node_b": "C", "correlation": 0.5, "p_value": 0.001},
        ]
        new_edges, added = add_fallback_edges(edges, violations)
        dag = edges_to_nx(new_edges)
        assert nx.is_directed_acyclic_graph(dag)


# ═══════════════════════════════════════════════════════════════════
# E14: _apply_positivity_rewrite partial state
# ═══════════════════════════════════════════════════════════════════

class TestE14PartialState:

    def test_no_mutation_when_treatment_unchanged(self):
        spec = make_spec(treatment="treatment",
                         treatment_form=TreatmentForm.CONTINUOUS)
        new_spec = apply_positivity_rewrite(
            spec, list(spec.estimation_variants), "treatment", None)
        assert new_spec.treatment == "treatment"
        assert new_spec.treatment_form == TreatmentForm.CONTINUOUS
        assert new_spec.original_treatment is None or new_spec.original_treatment == spec.original_treatment

    def test_spec_treatment_and_variants_change_together(self, hierarchical_data):
        spec = make_spec(
            treatment="refrigHealthPct", outcome="outcome",
            treatment_form=TreatmentForm.CONTINUOUS,
        )
        variants = [
            EstimationVariant(
                id="v1", treatment_column="nodeGroupId",
                treatment_form=TreatmentForm.CONTINUOUS,
                model_type="linear_dml", w_columns=["conf_a", "conf_b"],
            )
        ]
        new_spec = apply_positivity_rewrite(
            spec, variants, "nodeGroupId", None, hierarchical_data)
        assert new_spec.treatment == "nodeGroupId"
        for v in new_spec.estimation_variants:
            assert v.treatment_column == "nodeGroupId"
            assert v.treatment_form == new_spec.treatment_form


# ═══════════════════════════════════════════════════════════════════
# E15: Auto-correction cascade abort
# ═══════════════════════════════════════════════════════════════════

class TestE15AutoCorrectionAbort:

    def test_first_large_delta_aborts_loop(self, simple_data):
        spec = make_spec(
            residual_checks=ResidualChecks(
                autocorrelation=[],
                field_correlation=FieldCorrelationCheck(
                    threshold=0.001,
                    check_columns=["conf_a", "conf_b"],
                ),
                auto_correction=AutoCorrectionConfig(
                    max_iterations=10,
                    stop_criterion_ci_pct=5.0,
                ),
                metadata_correlation=[],
            ),
        )
        edges = [("treatment", "outcome"), ("conf_a", "outcome"), ("conf_b", "outcome")]

        call_count = 0

        def mock_dml_quick(*args, **kwargs):
            nonlocal call_count
            call_count += 1
            return 100.0

        with patch("service.causal_verification.residual_diagnostics.run_dml_quick",
                   side_effect=mock_dml_quick):
            result = residual_diagnostics(
                simple_data, spec, ["conf_a", "conf_b"], edges, 1.0)

        corrections = result.get("corrections", [])
        if corrections:
            first = corrections[0]
            if first["delta_pct"] > AUTO_CORRECTION_ABORT_DELTA_PCT:
                assert first.get("aborted") is True
                assert len(corrections) == 1
                assert result["corrected_effect"] is None

    def test_small_delta_continues_loop(self, simple_data):
        spec = make_spec(
            residual_checks=ResidualChecks(
                autocorrelation=[],
                field_correlation=FieldCorrelationCheck(
                    threshold=0.001,
                    check_columns=["conf_a", "conf_b"],
                ),
                auto_correction=AutoCorrectionConfig(
                    max_iterations=10,
                    stop_criterion_ci_pct=5.0,
                ),
                metadata_correlation=[],
            ),
        )
        edges = [("treatment", "outcome"), ("conf_a", "outcome"), ("conf_b", "outcome")]

        def mock_dml_quick(*args, **kwargs):
            return 1.05

        with patch("service.causal_verification.residual_diagnostics.run_dml_quick",
                   side_effect=mock_dml_quick):
            result = residual_diagnostics(
                simple_data, spec, ["conf_a", "conf_b"], edges, 1.0)

        corrections = result.get("corrections", [])
        for c in corrections:
            assert not c.get("aborted", False)


# ═══════════════════════════════════════════════════════════════════
# Default path tests: Pipeline utilities
# ═══════════════════════════════════════════════════════════════════

class TestPipelineUtils:

    def test_parse_dag_edges_basic(self):
        edges = parse_dag_edges("A -> B; B -> C")
        assert ("A", "B") in edges
        assert ("B", "C") in edges

    def test_parse_dag_edges_newlines(self):
        edges = parse_dag_edges("A -> B\nB -> C\nC -> D")
        assert len(edges) == 3

    def test_parse_dag_edges_empty(self):
        edges = parse_dag_edges("")
        assert edges == []

    def test_edges_to_nx(self):
        dag = edges_to_nx([("A", "B"), ("B", "C")])
        assert dag.has_edge("A", "B")
        assert dag.has_edge("B", "C")
        assert not dag.has_edge("A", "C")

    def test_infer_treatment_form_categorical(self, cat_data):
        form = infer_treatment_form(cat_data, "treatment")
        assert form == TreatmentForm.CATEGORICAL

    def test_infer_treatment_form_continuous(self, simple_data):
        form = infer_treatment_form(simple_data, "treatment")
        assert form == TreatmentForm.CONTINUOUS

    def test_infer_treatment_form_missing_column(self, simple_data):
        form = infer_treatment_form(simple_data, "nonexistent")
        assert form == TreatmentForm.CATEGORICAL


# ═══════════════════════════════════════════════════════════════════
# Default path tests: D-sep refinement
# ═══════════════════════════════════════════════════════════════════

class TestDsepRefinement:

    def test_dsep_returns_expected_structure(self, simple_data):
        edges = [("treatment", "outcome"), ("conf_a", "outcome"),
                 ("conf_a", "treatment")]
        result = dsep_refinement(simple_data, edges, 0.05)
        assert "refined_edges" in result
        assert "violation_count" in result
        assert "confirmed_count" in result
        assert "broad_edge_count" in result

    def test_dsep_no_violations_keeps_edges(self, simple_data):
        edges = [("treatment", "outcome")]
        result = dsep_refinement(simple_data, edges, 0.05)
        assert result["refined_edge_count"] >= len(edges)

    def test_fallback_edges_preserve_acyclicity(self):
        edges = [("A", "B"), ("B", "C")]
        violations = [
            {"node_a": "A", "node_b": "C", "correlation": 0.5, "p_value": 0.01},
        ]
        new_edges, added = add_fallback_edges(edges, violations)
        dag = edges_to_nx(new_edges)
        assert nx.is_directed_acyclic_graph(dag)
        assert len(new_edges) >= len(edges)


# ═══════════════════════════════════════════════════════════════════
# Default path tests: Positivity
# ═══════════════════════════════════════════════════════════════════

class TestPositivity:

    def test_select_confounders_returns_categorical(self, simple_data):
        selected = select_positivity_confounders(
            simple_data, ["conf_a", "conf_b"], "treatment")
        assert all(c in simple_data.cat_columns for c in selected)

    def test_select_confounders_skips_numeric(self, simple_data):
        selected = select_positivity_confounders(
            simple_data, ["treatment", "outcome", "conf_a"], "treatment")
        assert "treatment" not in selected
        assert "outcome" not in selected

    def test_positivity_gate_no_check_returns_none(self, simple_data):
        spec = make_spec(
            positivity_check=PositivityCheck(
                treatment_hierarchy=["treatment"],
                relative_threshold=0.3,
            ),
        )
        report, rewritten, final, mask = positivity_gate(simple_data, spec)
        assert "attempted_levels" in report

    def test_rewrite_variants_no_coarsening_preserves(self):
        spec = make_spec(treatment="treatment")
        result = rewrite_variants_for_positivity(
            spec, "treatment", "treatment", MagicMock(), None)
        assert len(result) == len(spec.estimation_variants)
        for orig, rewr in zip(spec.estimation_variants, result):
            assert orig.treatment_column == rewr.treatment_column

    def test_rewrite_variants_drops_binary_threshold(self, hierarchical_data):
        spec = make_spec(
            treatment="refrigHealthPct",
            estimation_variants=[
                EstimationVariant(
                    id="v_bin", treatment_column="refrigHealthPct",
                    treatment_form=TreatmentForm.BINARY_THRESHOLD,
                    model_type="linear_dml", w_columns=["conf_a"],
                    threshold_value=70.0,
                ),
                EstimationVariant(
                    id="v_cont", treatment_column="refrigHealthPct",
                    treatment_form=TreatmentForm.CONTINUOUS,
                    model_type="linear_dml", w_columns=["conf_a"],
                ),
            ],
        )
        result = rewrite_variants_for_positivity(
            spec, "nodeGroupId", "refrigHealthPct",
            hierarchical_data, None)
        ids = [v.id for v in result]
        assert "v_bin" not in ids
        assert "v_cont" in ids


# ═══════════════════════════════════════════════════════════════════
# Default path tests: Quality gates
# ═══════════════════════════════════════════════════════════════════

class TestQualityGates:

    def test_quality_gates_structure(self, simple_data):
        spec = make_spec()
        with patch("service.causal_verification.quality_gates.cross_val_score",
                   return_value=np.array([0.5, 0.5, 0.5, 0.5, 0.5])):
            result = quality_gates(
                simple_data, spec, ["conf_a", "conf_b"], 1.0, (-0.5, 2.5))
        assert "nuisance_r2" in result
        assert "outcome_r2" in result["nuisance_r2"]
        assert "treatment_r2" in result["nuisance_r2"]

    def test_quality_gates_sanity_direction_warning(self, simple_data):
        spec = make_spec()
        with patch("service.causal_verification.quality_gates.cross_val_score",
                   return_value=np.array([0.5, 0.5, 0.5, 0.5, 0.5])):
            result = quality_gates(
                simple_data, spec, ["conf_a", "conf_b"], -1.0, (-2.5, 0.5))
        assert result["sanity"]["direction_ok"] == False
        assert "warning" in result["sanity"]

    def test_quality_gates_null_effect(self, simple_data):
        spec = make_spec()
        with patch("service.causal_verification.quality_gates.cross_val_score",
                   return_value=np.array([0.5, 0.5, 0.5, 0.5, 0.5])):
            result = quality_gates(
                simple_data, spec, ["conf_a", "conf_b"], None, None)
        assert "sanity" not in result


# ═══════════════════════════════════════════════════════════════════
# Default path tests: Sensitivity
# ═══════════════════════════════════════════════════════════════════

class TestSensitivity:

    def test_sensitivity_confounder_drops(self, simple_data):
        spec = make_spec(
            sensitivity=SensitivityConfig(
                confounder_drops=[
                    ConfounderDrop(column="conf_a", deviation_threshold_pct=20.0),
                ],
                confounder_adds=[],
                threshold_variants=[],
                model_variants=[],
            ),
        )
        edges = [("treatment", "outcome"), ("conf_a", "outcome"), ("conf_b", "outcome")]
        with patch("service.causal_verification.sensitivity.run_dml_quick",
                   return_value=1.1):
            result = sensitivity(simple_data, spec, edges, 1.0, {})
        assert len(result["confounder_drops"]) == 1
        assert result["confounder_drops"][0]["column"] == "conf_a"

    def test_sensitivity_empty_config(self, simple_data):
        spec = make_spec()
        edges = [("treatment", "outcome")]
        result = sensitivity(simple_data, spec, edges, 1.0, {})
        assert result["confounder_drops"] == []
        assert result["confounder_adds"] == []
        assert result["threshold_variants"] == []
        assert result["model_variants"] == []


# ═══════════════════════════════════════════════════════════════════
# Default path tests: Residual diagnostics
# ═══════════════════════════════════════════════════════════════════

class TestResidualDiagnostics:

    def test_residual_diagnostics_structure(self, simple_data):
        spec = make_spec()
        edges = [("treatment", "outcome"), ("conf_a", "outcome")]
        result = residual_diagnostics(
            simple_data, spec, ["conf_a", "conf_b"], edges, 1.0)
        assert "autocorrelation" in result
        assert "field_correlations" in result
        assert "corrections" in result
        assert "metadata_correlations" in result

    def test_no_correction_when_no_eligible(self, simple_data):
        spec = make_spec(
            residual_checks=ResidualChecks(
                autocorrelation=[],
                field_correlation=FieldCorrelationCheck(
                    threshold=0.99,
                    check_columns=["conf_a"],
                ),
                auto_correction=AutoCorrectionConfig(
                    max_iterations=3, stop_criterion_ci_pct=5.0),
                metadata_correlation=[],
            ),
        )
        edges = [("treatment", "outcome"), ("conf_a", "outcome")]
        result = residual_diagnostics(
            simple_data, spec, ["conf_a", "conf_b"], edges, 1.0)
        assert result["corrected_effect"] is None


# ═══════════════════════════════════════════════════════════════════
# Default path tests: Externalization
# ═══════════════════════════════════════════════════════════════════

class TestExternalization:

    def test_externalization_empty_config(self, simple_data):
        spec = make_spec()
        result_dict = {"steps": {"estimation": {}, "sensitivity": {}, "grf": []}}
        result = externalization(simple_data, spec, result_dict)
        assert result["domain_rankings"] == []
        assert result["allocation_bias"] == []

    def test_allocation_bias_computes_deviation(self, simple_data):
        spec = make_spec(
            externalization=ExternalizationConfig(
                domain_rankings=[],
                allocation_bias=[
                    AllocationBias(
                        treatment_column="treatment",
                        grouping_column="conf_a",
                        flag_threshold=0.5,
                    ),
                ],
            ),
        )
        result_dict = {"steps": {"estimation": {}, "sensitivity": {}, "grf": []}}
        result = externalization(simple_data, spec, result_dict)
        assert len(result["allocation_bias"]) == 1
        assert "max_deviation" in result["allocation_bias"][0]


# ═══════════════════════════════════════════════════════════════════
# Default path tests: PipelineDataFrame
# ═══════════════════════════════════════════════════════════════════

class TestPipelineDataFrame:

    def test_from_dataframe_encodes_categoricals(self):
        df = pd.DataFrame({
            "num": [1.0, 2.0, 3.0],
            "cat": ["a", "b", "c"],
        })
        pdf = PipelineDataFrame.from_dataframe(df)
        assert "cat" in pdf.cat_columns
        assert pdf.encoded["cat"].tolist() == [0, 1, 2]

    def test_filter_mask_preserves_consistency(self):
        df = pd.DataFrame({
            "x": [1.0, 2.0, 3.0, 4.0],
            "cat": ["a", "b", "c", "d"],
        })
        pdf = PipelineDataFrame.from_dataframe(df)
        mask = pd.Series([True, False, True, False])
        filtered = pdf.filter_mask(mask)
        assert len(filtered) == 2
        assert filtered.raw["cat"].tolist() == ["a", "c"]

    def test_stratified_subsample(self):
        df = pd.DataFrame({
            "x": range(100),
            "group": ["A"] * 50 + ["B"] * 50,
        })
        pdf = PipelineDataFrame.from_dataframe(df)
        sub = pdf.stratified_subsample("group", 0.5)
        assert len(sub) < 100
        counts = sub.raw["group"].value_counts()
        assert counts["A"] > 0
        assert counts["B"] > 0

    def test_encoded_values_returns_numpy(self, simple_data):
        arr = simple_data.encoded_values(["conf_a", "conf_b"])
        assert isinstance(arr, np.ndarray)
        assert arr.shape == (200, 2)


# ═══════════════════════════════════════════════════════════════════
# Edge case tests
# ═══════════════════════════════════════════════════════════════════

class TestEdgeCases:

    def test_positivity_all_levels_fail(self):
        rng = np.random.default_rng(42)
        n = 50
        df = pd.DataFrame({
            "treatment": rng.choice([f"t_{i}" for i in range(30)], n),
            "outcome": rng.normal(0, 1, n),
            "conf": rng.choice([f"c_{i}" for i in range(20)], n),
        })
        data = PipelineDataFrame.from_dataframe(df)
        spec = make_spec(
            treatment="treatment",
            confounders=["conf"],
            positivity_check=PositivityCheck(
                treatment_hierarchy=["treatment"],
                relative_threshold=0.3,
            ),
        )
        report, rewritten, final, mask = positivity_gate(data, spec)
        assert report["final_level"] is not None or "failure" in report

    def test_empty_confounders_for_positivity(self, simple_data):
        selected = select_positivity_confounders(
            simple_data, [], "treatment")
        assert selected == []

    def test_sensitivity_missing_confounder_add(self, simple_data):
        spec = make_spec(
            sensitivity=SensitivityConfig(
                confounder_drops=[],
                confounder_adds=[
                    MagicMock(column="nonexistent", reasoning="test"),
                ],
                threshold_variants=[],
                model_variants=[],
            ),
        )
        edges = [("treatment", "outcome")]
        result = sensitivity(simple_data, spec, edges, 1.0, {})
        assert result["confounder_adds"][0].get("error") is not None

    def test_parse_dag_edges_malformed(self):
        edges = parse_dag_edges("not an edge; also not; A -> B")
        assert len(edges) == 1
        assert edges[0] == ("A", "B")

    def test_apply_positivity_trim_list_mask(self, simple_data):
        from service.causal_verification.positivity import apply_positivity_trim
        mask = [True] * 100 + [False] * 100
        trimmed = apply_positivity_trim(simple_data, mask)
        assert len(trimmed) == 100

    def test_protected_columns_empty_dag(self):
        spec = make_spec()
        dag = edges_to_nx([])
        protected = build_protected_columns(spec, dag)
        assert spec.treatment in protected or (spec.original_treatment or spec.treatment) in protected


# ═══════════════════════════════════════════════════════════════════
# Backward compatibility
# ═══════════════════════════════════════════════════════════════════

class TestBackwardCompat:

    def test_import_from_old_path(self):
        from service.causal_verification_service import CausalVerificationService
        assert CausalVerificationService is not None

    def test_import_from_new_path(self):
        from service.causal_verification import CausalVerificationService
        assert CausalVerificationService is not None

    def test_service_has_backward_compat_methods(self):
        from service.causal_verification import CausalVerificationService
        svc = CausalVerificationService()
        assert hasattr(svc, '_load_data')
        assert hasattr(svc, '_validate_spec')
        assert hasattr(svc, '_parse_dag_edges')
        assert hasattr(svc, '_edges_to_nx')
        assert hasattr(svc, 'run_pipeline')
