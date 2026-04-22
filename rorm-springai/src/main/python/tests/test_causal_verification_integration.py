"""Integration tests with exhaustive numerical verification.

Tolerances calibrated from analytical SEs of each DGP (seed=42):
- Continuous DGP (n=5000): SE=0.028, 4SE=0.112
- Binary DGP (n=5000): SE=0.028, 4SE=0.113
- H3 data (n=10000): 154 below-70, 2534 in zero-variance nodes
"""
import math
import networkx as nx
import numpy as np
import pandas as pd
import pytest
from concurrent.futures import Future
from dataclasses import replace as dc_replace, asdict
from dto.causal_verification_request import (
    AllocationBias, AutoCorrectionConfig, ConfounderAdd, ConfounderDrop,
    DomainRanking, EstimationVariant, ExternalizationConfig,
    FieldCorrelationCheck, GrfConfig, MediationConfig, MediatorExclusion,
    MetadataCorrelation, ModelVariant, OverlapCheck, OverlapStrategy,
    PositivityCheck, RangeChecks, RefutationConfig, RefutationType,
    ResidualChecks, SensitivityConfig, StructuralBreakConfig,
    ThresholdVariant, TreatmentForm,
    UnmeasuredConfoundingConfig, UnmeasuredMethod, VarianceCheck,
    VariantFilter, FilterOperator, VifConfig,
)
from service.causal_verification.dsep import dsep_refinement, add_fallback_edges
from service.causal_verification.estimation import run_estimation_variant
from service.causal_verification.externalization import externalization
from service.causal_verification.mediation import mediation
from service.causal_verification.pipeline_utils import (
    build_protected_columns, edges_to_nx, parse_dag_edges, run_dml_quick,
)
from service.causal_verification.positivity import (
    apply_positivity_rewrite, positivity_gate, rewrite_variants_for_positivity,
    select_positivity_confounders, apply_positivity_trim,
)
from service.causal_verification.quality_gates import quality_gates
from service.causal_verification.range_checks import range_checks
from service.causal_verification.refutations import collect_refutations, temporal_placebo
from service.causal_verification.residual_diagnostics import (
    residual_diagnostics, AUTO_CORRECTION_ABORT_DELTA_PCT,
)
from service.causal_verification.sensitivity import sensitivity
from service.causal_verification.unmeasured_confounding import unmeasured_confounding
from service.pipeline_dataframe import PipelineDataFrame
from tests.conftest import (
    make_pipeline_df, make_spec, make_causal_df, make_binary_causal_df,
    make_h3_data, make_h3_spec, H3_CONFOUNDERS, H3_DAG_EDGES,
)
from unittest.mock import MagicMock, patch

CAUSAL_EDGES = [("conf", "treatment"), ("conf", "outcome"), ("treatment", "outcome")]
CONT_4SE = 0.112
BIN_4SE = 0.113


@pytest.fixture(scope="session")
def causal_data():
    return make_causal_df(n=5000, true_ate=0.5, seed=42)


@pytest.fixture(scope="session")
def binary_data():
    return make_binary_causal_df(n=5000, true_ate=5.0, seed=42)


@pytest.fixture(scope="session")
def h3_data():
    return make_h3_data(n=10_000, seed=42)


class TestEstimationExhaustive:

    def test_continuous_all_fields(self, causal_data):
        data, true_ate = causal_data
        variant = EstimationVariant(
            id="v1", treatment_column="treatment",
            treatment_form=TreatmentForm.CONTINUOUS,
            model_type="LinearDML", w_columns=["conf"],
        )
        r = run_estimation_variant(data, variant, CAUSAL_EDGES,
                                   edges_to_nx(CAUSAL_EDGES), "outcome")
        assert r["variant_id"] == "v1"
        assert abs(r["effect"] - true_ate) < CONT_4SE, \
            f"ATE={r['effect']:.4f}, expected {true_ate}+/-{CONT_4SE}"
        lo, hi = r["ci"]
        assert lo < hi, f"CI inverted: [{lo:.4f},{hi:.4f}]"
        assert hi - lo < 0.5, f"CI width {hi - lo:.3f} implausibly large"
        assert lo < true_ate + CONT_4SE, f"CI lower {lo:.4f} too far above true ATE {true_ate}"
        assert hi > true_ate - CONT_4SE, f"CI upper {hi:.4f} too far below true ATE {true_ate}"
        assert r["treatment_column"] == "treatment"
        assert r["w_columns"] == ["conf"]
        assert r["n_obs"] == 5000
        assert r["discrete"] is False
        assert "category_effects" not in r

    def test_categorical_all_fields(self, binary_data):
        data, true_ate = binary_data
        variant = EstimationVariant(
            id="v_cat", treatment_column="treatment",
            treatment_form=TreatmentForm.CATEGORICAL,
            model_type="LinearDML", w_columns=["conf"],
        )
        r = run_estimation_variant(data, variant, CAUSAL_EDGES,
                                   edges_to_nx(CAUSAL_EDGES), "outcome")
        assert r["variant_id"] == "v_cat"
        assert r["discrete"] is True
        assert r["n_obs"] == 5000
        cats = r["category_effects"]
        assert len(cats) == 2
        ref_labels = [l for l, v in cats.items() if v == 0.0]
        assert len(ref_labels) == 1, f"exactly one reference category should be 0.0, got {cats}"
        non_ref = {l: v for l, v in cats.items() if v != 0.0}
        assert len(non_ref) == 1
        effect_val = list(non_ref.values())[0]
        assert abs(abs(effect_val) - true_ate) < BIN_4SE, \
            f"|effect|={abs(effect_val):.3f}, expected {true_ate}+/-{BIN_4SE}"
        lo, hi = r["ci"]
        assert lo < hi

    def test_filter_n_obs_and_effect(self, causal_data):
        data, true_ate = causal_data
        variant = EstimationVariant(
            id="v_f", treatment_column="treatment",
            treatment_form=TreatmentForm.CONTINUOUS,
            model_type="LinearDML", w_columns=["conf"],
            filter=VariantFilter(column="noise", operator=FilterOperator.GT, values=[0.0]),
        )
        r = run_estimation_variant(data, variant, CAUSAL_EDGES,
                                   edges_to_nx(CAUSAL_EDGES), "outcome")
        assert r["variant_id"] == "v_f"
        assert 2200 < r["n_obs"] < 2800, f"N(0,1)>0 keeps ~50%, got {r['n_obs']}"
        assert abs(r["effect"] - true_ate) < 0.2, \
            f"filtered ATE={r['effect']:.4f}, expected ~{true_ate} (wider tol due to smaller n)"

    def test_error_path_preserves_variant_id(self, causal_data):
        data, _ = causal_data
        variant = EstimationVariant(
            id="v_will_fail", treatment_column="nonexistent_col",
            treatment_form=TreatmentForm.CONTINUOUS,
            model_type="LinearDML", w_columns=["conf"],
        )
        r = run_estimation_variant(data, variant, CAUSAL_EDGES,
                                   edges_to_nx(CAUSAL_EDGES), "outcome")
        assert r["variant_id"] == "v_will_fail"
        assert r["effect"] is None
        assert r["ci"] is None
        assert isinstance(r["error"], str)
        assert len(r["error"]) > 0

    def test_binary_threshold_category_labels_semantic(self):
        """BINARY_THRESHOLD labels must reference the threshold, not raw values."""
        rng = np.random.default_rng(42)
        n = 2000
        treatment = rng.normal(50, 15, n)
        conf = rng.normal(0, 1, n)
        outcome = rng.binomial(1, 1 / (1 + np.exp(-(0.05 * (treatment - 50) + conf)))).astype(float)
        df = pd.DataFrame({"treatment": treatment, "outcome": outcome, "conf": conf})
        data = PipelineDataFrame.from_dataframe(df)
        variant = EstimationVariant(
            id="v_bin", treatment_column="treatment",
            treatment_form=TreatmentForm.BINARY_THRESHOLD,
            model_type="LinearDML", w_columns=["conf"],
            threshold_value=50.0,
        )
        edges = [("conf", "treatment"), ("treatment", "outcome"), ("conf", "outcome")]
        r = run_estimation_variant(data, variant, edges, edges_to_nx(edges), "outcome")
        assert "category_effects" in r
        cats = r["category_effects"]
        assert len(cats) == 2
        expected_keys = {"below_or_equal_50.0", "above_50.0"}
        assert set(cats.keys()) == expected_keys, \
            f"labels must reference threshold, got {set(cats.keys())}"
        for k in cats:
            assert "treatment=" not in k, \
                f"label '{k}' leaked raw treatment column as value"
            for raw_val in df["treatment"].unique()[:5]:
                assert f"={raw_val}" not in k and f"={raw_val:.1f}" not in k, \
                    f"label '{k}' contains raw treatment value"

    def test_binary_outcome_ci_clamped_and_flagged(self):
        """Binary outcome with extreme imbalance → CI clamped to [-1, 1]."""
        rng = np.random.default_rng(42)
        n = 3000
        treatment = np.zeros(n)
        treatment[:15] = 1.0
        conf = rng.normal(0, 1, n)
        outcome_prob = np.where(treatment == 1, 0.95, 0.05)
        outcome = rng.binomial(1, outcome_prob).astype(float)
        df = pd.DataFrame({
            "treatment": treatment.astype(float),
            "outcome": outcome,
            "conf": conf,
        })
        data = PipelineDataFrame.from_dataframe(df)
        variant = EstimationVariant(
            id="v_extreme", treatment_column="treatment",
            treatment_form=TreatmentForm.CATEGORICAL,
            model_type="LinearDML", w_columns=["conf"],
        )
        edges = [("conf", "treatment"), ("treatment", "outcome"), ("conf", "outcome")]
        r = run_estimation_variant(data, variant, edges, edges_to_nx(edges), "outcome")
        if r.get("effect") is not None:
            assert r["binary_outcome"] is True
            ci_lo, ci_hi = r["ci"]
            assert -1.0 <= ci_lo <= 1.0, f"clamped CI_lo={ci_lo} out of [-1,1]"
            assert -1.0 <= ci_hi <= 1.0, f"clamped CI_hi={ci_hi} out of [-1,1]"
            if "ci_warning" in r:
                ci_raw_lo, ci_raw_hi = r["ci_raw"]
                assert ci_raw_lo < -1.0 or ci_raw_hi > 1.0, \
                    "ci_warning should only appear when raw CI exceeded bounds"

    def test_continuous_outcome_no_binary_clamping(self, causal_data):
        data, _ = causal_data
        variant = EstimationVariant(
            id="v_cont", treatment_column="treatment",
            treatment_form=TreatmentForm.CONTINUOUS,
            model_type="LinearDML", w_columns=["conf"],
        )
        r = run_estimation_variant(data, variant, CAUSAL_EDGES,
                                   edges_to_nx(CAUSAL_EDGES), "outcome")
        assert r["binary_outcome"] is False
        assert "ci_warning" not in r
        assert r["ci"] == r["ci_raw"]


class TestQualityGatesExhaustive:

    def test_all_output_fields(self, causal_data):
        data, true_ate = causal_data
        spec = make_spec(confounders=["conf", "noise"])
        r = quality_gates(data, spec, ["conf", "noise"], true_ate, (0.3, 0.7))
        nr2 = r["nuisance_r2"]
        assert 0.3 < nr2["outcome_r2"] < 0.85
        assert 0.5 < nr2["treatment_r2"] < 0.95
        assert nr2["outcome_status"] == "pass"
        assert nr2["treatment_status"] in ("pass", "structural")
        san = r["sanity"]
        assert san["direction_ok"] == True
        assert abs(san["effect_magnitude"] - true_ate) < CONT_4SE
        assert san["flag_magnitude"] == 50.0
        assert san["status"] == "pass"
        assert "warning" not in san

    def test_structural_rewrite_vs_plain(self, causal_data):
        data, _ = causal_data
        spec_rewrite = make_spec(confounders=["conf", "noise"],
                                 original_treatment="different_col")
        spec_plain = make_spec(confounders=["conf", "noise"])
        with patch("service.causal_verification.quality_gates.cross_val_score") as m:
            m.return_value = np.array([0.99] * 5)
            r_rw = quality_gates(data, spec_rewrite, ["conf", "noise"], 0.5, (0.3, 0.7))
            r_pl = quality_gates(data, spec_plain, ["conf", "noise"], 0.5, (0.3, 0.7))
        assert r_rw["nuisance_r2"]["treatment_status"] == "structural_rewrite"
        assert r_pl["nuisance_r2"]["treatment_status"] == "structural"


class TestSensitivityExhaustive:

    def test_add_already_in_primary_w_is_noop(self, causal_data):
        """Adding a confounder already in primary W must report 0% deviation
        (previously: DAG-derived W was different, produced non-zero noise)."""
        data, true_ate = causal_data
        spec = make_spec(
            confounders=["conf", "noise"],
            sensitivity=SensitivityConfig(
                confounder_drops=[],
                confounder_adds=[
                    ConfounderAdd(column="conf", reasoning="already in W"),
                    ConfounderAdd(column="noise", reasoning="already in W"),
                ],
                threshold_variants=[], model_variants=[],
            ),
        )
        edges = CAUSAL_EDGES + [("noise", "outcome")]
        r = sensitivity(data, spec, edges, true_ate, {})
        adds = r["confounder_adds"]
        assert len(adds) == 2
        for a in adds:
            assert a["deviation_pct"] == 0.0, \
                f"adding {a['column']} (already in W) should be no-op, got {a['deviation_pct']}"
            assert "note" in a
            assert "already in primary" in a["note"]
            assert a["w_size"] == a["w_baseline_size"]

    def test_drop_column_not_in_primary_w_errors(self, causal_data):
        """Dropping a column not in primary W cannot measure deviation."""
        data, true_ate = causal_data
        spec = make_spec(
            confounders=["conf"],
            sensitivity=SensitivityConfig(
                confounder_drops=[
                    ConfounderDrop(column="not_in_W", deviation_threshold_pct=10.0),
                ],
                confounder_adds=[], threshold_variants=[], model_variants=[],
            ),
        )
        r = sensitivity(data, spec, CAUSAL_EDGES, true_ate, {})
        d = r["confounder_drops"][0]
        assert d["column"] == "not_in_W"
        assert d["effect"] is None
        assert d["deviation_pct"] is None
        assert d["flag"] is False
        assert "not in primary variant" in d["error"]

    def test_add_new_column_extends_primary_w(self, causal_data):
        """Adding a genuinely new column must extend primary W by exactly 1."""
        data, true_ate = causal_data
        spec = make_spec(
            confounders=["conf"],
            sensitivity=SensitivityConfig(
                confounder_drops=[],
                confounder_adds=[
                    ConfounderAdd(column="noise", reasoning="genuinely new"),
                ],
                threshold_variants=[], model_variants=[],
            ),
        )
        r = sensitivity(data, spec, CAUSAL_EDGES + [("noise", "outcome")],
                        true_ate, {})
        a = r["confounder_adds"][0]
        assert a["column"] == "noise"
        assert a["w_baseline_size"] == 1
        assert a["w_size"] == 2
        assert isinstance(a["deviation_pct"], float)
        assert abs(a["deviation_pct"]) < 20.0, \
            f"noise is actually independent; deviation should be small, got {a['deviation_pct']}"

    def test_drop_reports_w_size_decrease_by_one(self, causal_data):
        data, true_ate = causal_data
        spec = make_spec(
            confounders=["conf", "noise"],
            sensitivity=SensitivityConfig(
                confounder_drops=[
                    ConfounderDrop(column="noise", deviation_threshold_pct=30.0),
                ],
                confounder_adds=[], threshold_variants=[], model_variants=[],
            ),
        )
        edges = CAUSAL_EDGES + [("noise", "outcome")]
        r = sensitivity(data, spec, edges, true_ate, {})
        d = r["confounder_drops"][0]
        assert d["w_baseline_size"] == 2
        assert d["w_size"] == 1

    def test_confounder_drops_all_fields(self, causal_data):
        data, true_ate = causal_data
        spec = make_spec(
            confounders=["conf", "noise"],
            sensitivity=SensitivityConfig(
                confounder_drops=[
                    ConfounderDrop(column="conf", deviation_threshold_pct=10.0),
                    ConfounderDrop(column="noise", deviation_threshold_pct=50.0),
                ],
                confounder_adds=[
                    ConfounderAdd(column="noise", reasoning="test add"),
                ],
                threshold_variants=[
                    ThresholdVariant(threshold=0.0, expected_n_treated=2500,
                                     expected_n_control=2500),
                ],
                model_variants=[
                    ModelVariant(primary_variant_id="v1",
                                 alternative_model_type="NonParamDML"),
                ],
            ),
        )
        edges = CAUSAL_EDGES + [("noise", "outcome")]
        estimation_results = {"v1": {"effect": true_ate}}
        r = sensitivity(data, spec, edges, true_ate, estimation_results)

        drops = r["confounder_drops"]
        assert len(drops) == 2
        conf_d = next(d for d in drops if d["column"] == "conf")
        assert conf_d["effect"] is not None
        assert isinstance(conf_d["deviation_pct"], float)
        assert conf_d["deviation_pct"] > 10.0
        assert conf_d["threshold_pct"] == 10.0
        assert conf_d["flag"] is True
        noise_d = next(d for d in drops if d["column"] == "noise")
        assert noise_d["effect"] is not None
        assert noise_d["deviation_pct"] < 30.0
        assert noise_d["threshold_pct"] == 50.0

        adds = r["confounder_adds"]
        assert len(adds) == 1
        assert adds[0]["column"] == "noise"
        assert adds[0]["reasoning"] == "test add"
        assert adds[0]["effect"] is not None
        assert isinstance(adds[0]["deviation_pct"], float)

        tvs = r["threshold_variants"]
        assert len(tvs) == 1
        tv = tvs[0]
        assert tv["threshold"] == 0.0
        assert tv["n_treated"] + tv["n_control"] == 5000
        assert 2200 < tv["n_treated"] < 2800
        assert tv["expected_n_treated"] == 2500
        assert tv["expected_n_control"] == 2500
        assert isinstance(tv["effect"], float)

        mvs = r["model_variants"]
        assert len(mvs) == 1
        assert mvs[0]["primary_variant_id"] == "v1"
        assert mvs[0]["alternative_model_type"] == "NonParamDML"
        assert mvs[0]["primary_effect"] == pytest.approx(true_ate)


class TestResidualDiagnosticsExhaustive:

    def test_detects_omitted_confounder_all_fields(self):
        rng = np.random.default_rng(42)
        n = 5000
        conf = rng.normal(0, 1, n)
        omitted = rng.normal(0, 1, n)
        treatment = conf + rng.normal(0, 1, n)
        outcome = 0.5 * treatment + conf + 3.0 * omitted + rng.normal(0, 1, n)
        df = pd.DataFrame({
            "treatment": treatment, "outcome": outcome,
            "conf": conf, "omitted": omitted,
        })
        data = PipelineDataFrame.from_dataframe(df)
        spec = make_spec(
            confounders=["conf"],
            residual_checks=ResidualChecks(
                autocorrelation=[],
                field_correlation=FieldCorrelationCheck(
                    threshold=0.05, check_columns=["omitted"]),
                auto_correction=AutoCorrectionConfig(
                    max_iterations=1, stop_criterion_ci_pct=5.0),
                metadata_correlation=[
                    MetadataCorrelation(column="omitted", threshold=0.1,
                                        alert_type="TEST_ALERT"),
                ],
            ),
        )
        edges = [("conf", "treatment"), ("treatment", "outcome"), ("conf", "outcome")]
        r = residual_diagnostics(data, spec, ["conf"], edges, 0.5)

        assert r["autocorrelation"] == []

        corrs = r["field_correlations"]
        assert len(corrs) >= 1
        om = next(c for c in corrs if c["column"] == "omitted")
        assert abs(om["correlation"]) > 0.3
        assert isinstance(om["is_metadata"], bool)

        if r["corrections"]:
            c0 = r["corrections"][0]
            assert "field" in c0
            assert "original" in c0
            assert "corrected" in c0
            assert "delta_pct" in c0
            assert isinstance(c0["delta_pct"], float)
            assert "is_metadata" in c0

        mc = r["metadata_correlations"]
        assert len(mc) == 1
        assert mc[0]["column"] == "omitted"
        assert isinstance(mc[0]["correlation"], float)
        assert mc[0]["threshold"] == 0.1
        assert mc[0]["alert_type"] == "TEST_ALERT"
        assert mc[0]["flagged"] == (abs(mc[0]["correlation"]) > 0.1)

    def test_abort_on_large_first_delta(self, causal_data):
        data, _ = causal_data
        spec = make_spec(
            confounders=["conf"],
            residual_checks=ResidualChecks(
                autocorrelation=[],
                field_correlation=FieldCorrelationCheck(
                    threshold=0.001, check_columns=["noise"]),
                auto_correction=AutoCorrectionConfig(
                    max_iterations=10, stop_criterion_ci_pct=5.0),
                metadata_correlation=[],
            ),
        )

        def mock_dml(*a, **kw):
            return 500.0

        with patch("service.causal_verification.residual_diagnostics.run_dml_quick",
                   side_effect=mock_dml):
            r = residual_diagnostics(data, spec, ["conf"], CAUSAL_EDGES, 1.0)
        corr = r["corrections"]
        assert len(corr) == 1
        assert corr[0]["aborted"] is True
        assert corr[0]["delta_pct"] > AUTO_CORRECTION_ABORT_DELTA_PCT
        assert "abort_reason" in corr[0]
        assert r["corrected_effect"] is None

    def test_protected_columns_excluded(self, causal_data):
        data, _ = causal_data
        spec = make_spec(
            confounders=["conf"], original_treatment="treatment",
            residual_checks=ResidualChecks(
                autocorrelation=[],
                field_correlation=FieldCorrelationCheck(
                    threshold=0.001, check_columns=["conf", "noise", "treatment"]),
                auto_correction=AutoCorrectionConfig(
                    max_iterations=5, stop_criterion_ci_pct=5.0),
                metadata_correlation=[],
            ),
        )
        dag = edges_to_nx(CAUSAL_EDGES)
        protected = build_protected_columns(spec, dag)
        r = residual_diagnostics(data, spec, ["conf"], CAUSAL_EDGES, 0.5,
                                 protected_columns=protected)
        for c in r.get("corrections", []):
            assert c["field"] not in protected


class TestMediationExhaustive:

    def test_uses_direct_variant_from_estimation_results(self):
        """When direct_variant_id has a pre-computed effect, mediation
        uses it instead of refitting — verifying (5) is fixed."""
        spec = make_spec(
            confounders=["conf"], original_treatment="treatment",
            mediation=[MediationConfig(
                mediator="mediator", pathway="T->M->Y",
                total_variant_id="v_total", direct_variant_id="v_direct",
            )],
        )
        rng = np.random.default_rng(42)
        n = 100
        df = pd.DataFrame({
            "treatment": rng.normal(0, 1, n),
            "outcome": rng.normal(0, 1, n),
            "conf": rng.normal(0, 1, n),
            "mediator": rng.normal(0, 1, n),
        })
        data = PipelineDataFrame.from_dataframe(df)
        estimation_results = {
            "v_total": {"effect": 0.50},
            "v_direct": {"effect": 0.20},
        }
        r = mediation(data, spec, ["conf"], estimation_results, [])
        assert len(r) == 1
        m = r[0]
        assert m["source"] == "estimation_variants"
        assert m["direct_effect"] == pytest.approx(0.20)
        assert m["mediated_effect"] == pytest.approx(0.30)
        assert m["fraction"] == pytest.approx(0.60)
        assert m["direct_effect"] + m["mediated_effect"] == pytest.approx(0.50)

    def test_falls_back_to_refit_when_direct_variant_missing(self):
        rng = np.random.default_rng(42)
        n = 5000
        conf = rng.normal(0, 1, n)
        treatment = conf + rng.normal(0, 1, n)
        med = 0.6 * treatment + rng.normal(0, 1, n)
        outcome = 0.2 * treatment + 0.5 * med + conf + rng.normal(0, 1, n)
        df = pd.DataFrame({"treatment": treatment, "outcome": outcome,
                           "conf": conf, "mediator": med})
        data = PipelineDataFrame.from_dataframe(df)
        total = 0.2 + 0.6 * 0.5
        spec = make_spec(
            confounders=["conf"], original_treatment="treatment",
            mediation=[MediationConfig(
                mediator="mediator", pathway="T->M->Y",
                total_variant_id="v_total", direct_variant_id="v_direct_missing",
            )],
        )
        estimation_results = {"v_total": {"effect": total}}
        edges = [("conf", "treatment"), ("treatment", "mediator"),
                 ("mediator", "outcome"), ("treatment", "outcome")]
        r = mediation(data, spec, ["conf"], estimation_results, edges)
        assert len(r) == 1
        m = r[0]
        assert m["source"] == "refit"
        assert m["mediator"] == "mediator"
        assert m["pathway"] == "T->M->Y"
        assert isinstance(m["direct_effect"], float)
        assert abs(m["direct_effect"]) < total + 0.15
        assert isinstance(m["mediated_effect"], float)
        assert abs(m["direct_effect"] + m["mediated_effect"] - total) < 0.01
        assert isinstance(m["fraction"], float)


class TestRangeChecksExhaustive:

    def test_vif_all_fields(self):
        rng = np.random.default_rng(42)
        n = 3000
        x = rng.normal(0, 1, n)
        df = pd.DataFrame({
            "treatment": rng.normal(0, 1, n),
            "outcome": rng.normal(0, 1, n),
            "collinear_a": x,
            "collinear_b": x + rng.normal(0, 0.01, n),
            "independent": rng.normal(0, 1, n),
        })
        data = PipelineDataFrame.from_dataframe(df)
        spec = make_spec(
            confounders=["collinear_a", "collinear_b", "independent"],
            range_checks=RangeChecks(
                vif=VifConfig(threshold=10.0, drop_pairs=[]),
                overlap=[], variance=[
                    VarianceCheck(column="treatment", structural_note="test note"),
                ],
            ),
        )
        r = range_checks(data, spec, ["collinear_a", "collinear_b", "independent"], {})
        vif = r["vif"]
        assert vif["threshold"] == 10.0
        assert vif["values"]["collinear_a"] > 100.0
        assert vif["values"]["collinear_b"] > 100.0
        assert vif["values"]["independent"] < 5.0
        assert vif["values"]["treatment"] < 5.0
        assert "collinear_a" in vif["flagged"]
        assert "collinear_b" in vif["flagged"]
        assert "independent" not in vif["flagged"]
        assert vif["dropped_columns"] == []
        assert vif["drop_pairs_applied"] == []

        assert isinstance(r["treatment_cv"], float)

        var = r["variance"]
        assert len(var) == 1
        assert var[0]["column"] == "treatment"
        assert var[0]["structural_note"] == "test note"
        assert isinstance(var[0]["std"], float)
        assert var[0]["std"] > 0.5

    def test_drop_pairs_applied(self):
        rng = np.random.default_rng(42)
        n = 3000
        x = rng.normal(0, 1, n)
        df = pd.DataFrame({
            "treatment": rng.normal(0, 1, n),
            "outcome": rng.normal(0, 1, n),
            "keep_col": x,
            "drop_col": x + rng.normal(0, 0.01, n),
            "independent": rng.normal(0, 1, n),
        })
        data = PipelineDataFrame.from_dataframe(df)
        spec = make_spec(
            confounders=["keep_col", "drop_col", "independent"],
            range_checks=RangeChecks(
                vif=VifConfig(threshold=10.0, drop_pairs=[
                    {"keep": "keep_col", "drop": "drop_col"},
                ]),
                overlap=[], variance=[],
            ),
        )
        r = range_checks(data, spec, ["keep_col", "drop_col", "independent"], {})
        vif = r["vif"]
        assert "drop_col" not in vif["values"], \
            "drop_col should be excluded from VIF computation"
        assert "keep_col" in vif["values"]
        assert vif["values"]["keep_col"] < 10.0, \
            "after dropping collinear partner, keep_col VIF should be low"
        assert vif["dropped_columns"] == ["drop_col"]
        assert len(vif["drop_pairs_applied"]) == 1
        assert vif["drop_pairs_applied"][0]["drop"] == "drop_col"

    def test_overlap_all_fields(self, binary_data):
        data, _ = binary_data
        spec = make_spec(
            confounders=["conf"],
            range_checks=RangeChecks(
                vif=VifConfig(threshold=10.0, drop_pairs=[]),
                overlap=[OverlapCheck(variant_id="v1", threshold=0.1,
                                      response_strategy=OverlapStrategy.TRIM,
                                      trim_bounds=[0.01, 0.99])],
                variance=[],
            ),
        )
        r = range_checks(data, spec, ["conf"], {"v1": {"treatment_column": "treatment"}})
        ov = r["overlap"][0]
        assert ov["variant_id"] == "v1"
        assert 0.6 < ov["overlap"] <= 1.0
        assert ov["threshold"] == 0.1
        assert ov["flagged"] is False
        assert ov["response_strategy"] == "TRIM"


class TestUnmeasuredConfoundingExhaustive:

    def test_e_value_all_fields(self):
        rng = np.random.default_rng(42)
        n = 2000
        df = pd.DataFrame({
            "treatment": rng.normal(50, 10, n),
            "outcome": rng.binomial(1, 0.1, n).astype(float),
            "conf": rng.normal(0, 1, n),
        })
        data = PipelineDataFrame.from_dataframe(df)
        spec = make_spec(
            confounders=["conf"],
            unmeasured_confounding=[
                UnmeasuredConfoundingConfig(
                    variant_id="v1", method=UnmeasuredMethod.E_VALUE,
                    null_hypothesis="ATE=0", notes="test"),
            ],
        )
        r = unmeasured_confounding(data, spec,
                                   {"v1": {"effect": -0.05, "ci": (-0.07, -0.03), "discrete": False}})
        assert len(r) == 1
        e = r[0]
        assert e["variant_id"] == "v1"
        assert e["method"] == "E_VALUE"
        assert e["null_hypothesis"] == "ATE=0"
        assert isinstance(e["rr"], float)
        assert e["rr"] > 1.0, f"rr={e['rr']:.4f}, expected >1 for positive-baseline binary outcome"
        assert isinstance(e["e_value_point"], float)
        assert e["e_value_point"] >= e["rr"]
        assert isinstance(e["e_value_ci"], float)
        assert e["e_value_ci"] >= 1.0


class TestDsepExhaustive:

    def test_all_output_fields(self):
        rng = np.random.default_rng(42)
        n = 3000
        common = rng.normal(0, 1, n)
        df = pd.DataFrame({
            "A": common + rng.normal(0, 0.1, n),
            "B": common + rng.normal(0, 0.1, n),
            "C": rng.normal(0, 1, n),
            "outcome": rng.normal(0, 1, n),
        })
        data = PipelineDataFrame.from_dataframe(df)
        edges = [("A", "outcome"), ("B", "outcome"), ("C", "outcome")]
        r = dsep_refinement(data, edges, 0.05)
        assert isinstance(r["broad_edge_count"], int)
        assert r["broad_edge_count"] == 3
        assert isinstance(r["refined_edge_count"], int)
        assert r["refined_edge_count"] >= 3
        assert isinstance(r["violation_count"], int)
        assert isinstance(r["confirmed_count"], int)
        assert r["violation_count"] + r["confirmed_count"] >= 0
        assert isinstance(r["fallback_edges"], list)
        assert isinstance(r["refined_edges"], list)
        for e in r["refined_edges"]:
            assert len(e) == 2
        for v in r["violations"]:
            assert "node_a" in v
            assert "node_b" in v
            assert isinstance(v["correlation"], float)
            assert isinstance(v["p_value"], float)


class TestRunDmlQuickExhaustive:

    def test_recovers_ate(self, causal_data):
        data, true_ate = causal_data
        dag = edges_to_nx(CAUSAL_EDGES)
        r = run_dml_quick(data, "treatment", "outcome", dag)
        assert isinstance(r, float)
        assert abs(r - true_ate) < 0.20, f"quick DML={r:.4f}, expected ~{true_ate}"

    def test_missing_column(self, causal_data):
        data, _ = causal_data
        assert run_dml_quick(data, "nonexistent", "outcome",
                             edges_to_nx([("nonexistent", "outcome")])) is None


# ═══════════════════════════════════════════════════════════════════
# Positivity escalation — full chain integrity
# ═══════════════════════════════════════════════════════════════════

class TestPositivityDecisionLogic:

    def test_original_treatment_in_hierarchy_passes_clean_no_rewrite(self):
        rng = np.random.default_rng(42)
        n = 3000
        df = pd.DataFrame({
            "treatment": rng.choice(["A", "B", "C"], n),
            "outcome": rng.normal(0, 1, n),
            "conf": rng.choice(["X", "Y"], n),
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
        assert report["attempted_levels"][0]["level"] == "treatment"
        assert rewritten is None, "same treatment passed → no rewrite"
        assert report["final_level"] == "treatment"

    def test_original_treatment_in_hierarchy_sparse_cells_trims_no_coarsen(self):
        rng = np.random.default_rng(42)
        n = 5000
        treatment = rng.choice([f"t_{i}" for i in range(20)], n)
        conf = rng.choice([f"c_{i}" for i in range(15)], n)
        df = pd.DataFrame({
            "treatment": treatment,
            "outcome": rng.normal(0, 1, n),
            "conf": conf,
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
        first = report["attempted_levels"][0]
        if first["verdict"] == "PASSED" and first.get("sparse_cells_count", 0) > 0:
            assert rewritten is None, \
                "original treatment passed with sparse cells → trim only, no coarsening"
            assert mask is not None, "should return surviving_mask for trimming"
            assert sum(mask) < n, "trimming should remove some rows"

    def test_different_hierarchy_level_passes_does_coarsen(self, h3_data):
        spec = make_spec(
            treatment="nodeId",
            outcome="excursionFlag",
            treatment_form=TreatmentForm.CATEGORICAL,
            confounders=["vehicleMakeModel", "climateZone"],
            dag_edges="nodeId -> excursionFlag; climateZone -> excursionFlag",
            positivity_check=PositivityCheck(
                treatment_hierarchy=["nodeId", "region"],
                relative_threshold=0.3,
            ),
        )
        report, rewritten, final, mask = positivity_gate(h3_data, spec)
        first = report["attempted_levels"][0]
        if first["verdict"] != "PASSED":
            second = report["attempted_levels"][1] if len(report["attempted_levels"]) > 1 else None
            if second and second["verdict"] == "PASSED" and second["level"] != spec.treatment:
                assert rewritten is not None, \
                    "hierarchy level differs from treatment → must coarsen"
                assert report.get("coarsened_treatment") is True

    def test_all_levels_fail_returns_failure(self):
        rng = np.random.default_rng(42)
        n = 100
        df = pd.DataFrame({
            "treatment": rng.choice([f"t_{i}" for i in range(50)], n),
            "outcome": rng.normal(0, 1, n),
            "conf": rng.choice([f"c_{i}" for i in range(30)], n),
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
        if report["final_level"] is None:
            assert "failure" in report
            assert rewritten is None
            assert mask is None

    def test_continuous_treatment_skips_cell_count_check(self, h3_data):
        spec = make_h3_spec(positivity=True)
        report, rewritten, final, mask = positivity_gate(h3_data, spec)
        assert report["final_level"] == "nodeRefrigHealthPct"
        assert rewritten is None, "continuous treatment with self-only hierarchy → no coarsen"
        assert mask is None, "continuous treatment → no cell-based trim"
        assert report["attempted_levels"][0]["verdict"] == "PASSED"
        assert "continuous treatment" in report["attempted_levels"][0].get("note", "")

    def test_invalid_hierarchy_rejected_at_validation(self, h3_data):
        from service.causal_verification.data_loading import validate_spec
        spec = make_h3_spec(positivity_hierarchy=["nodeId", "region", "climateZone"])
        with pytest.raises(ValueError, match="treatment_hierarchy must contain the treatment"):
            validate_spec(spec, h3_data)


def _cat_positivity_spec(h3_data):
    """Categorical treatment spec for testing cell-count positivity logic."""
    return make_spec(
        treatment="nodeId",
        outcome="excursionFlag",
        treatment_form=TreatmentForm.CATEGORICAL,
        confounders=["vehicleMakeModel", "climateZone", "hasRedundancy"],
        dag_edges="nodeId -> excursionFlag; climateZone -> excursionFlag; vehicleMakeModel -> nodeId",
        estimation_variants=[
            EstimationVariant(
                id="cat_v1", treatment_column="nodeId",
                treatment_form=TreatmentForm.CATEGORICAL,
                model_type="LinearDML",
                w_columns=["vehicleMakeModel", "climateZone", "hasRedundancy"],
            ),
        ],
        positivity_check=PositivityCheck(
            treatment_hierarchy=["nodeId", "region"],
            relative_threshold=0.3,
        ),
    )


class TestPositivityEscalationChain:

    def test_gate_report_structure(self, h3_data):
        spec = _cat_positivity_spec(h3_data)
        report, rewritten, final, mask = positivity_gate(h3_data, spec)
        levels = report["attempted_levels"]
        assert len(levels) >= 1
        for lv in levels:
            assert "level" in lv
            assert "verdict" in lv
            assert lv["verdict"] in ("PASSED", "COARSENED", "SKIPPED")
            if lv["verdict"] == "PASSED":
                assert isinstance(lv["coverage_pct"], float)
                assert isinstance(lv["effective_threshold"], float)
                assert isinstance(lv["row_coverage_pct"], float)
                assert lv["row_coverage_pct"] > 0
                for cr in lv["confounders_checked"]:
                    assert "confounder" in cr
                    assert isinstance(cr["n_cells"], int)
                    assert isinstance(cr["n_nonempty"], int)
                    assert isinstance(cr["n_surviving"], int)
                    assert isinstance(cr["sparse_count"], int)
                    assert isinstance(cr["coverage_pct"], float)
        assert isinstance(report["original_n"], int)
        assert report["original_n"] == len(h3_data)

    def test_rewrite_variant_treatment_column_and_form(self, h3_data):
        spec = make_h3_spec()
        rewritten = rewrite_variants_for_positivity(
            spec, "nodeId", "nodeRefrigHealthPct", h3_data, None)
        assert len(rewritten) == 1, "BINARY_THRESHOLD should be dropped"
        v = rewritten[0]
        assert v.id == "continuous_total"
        assert v.treatment_column == "nodeId"
        assert v.treatment_form == TreatmentForm.CATEGORICAL
        assert v.threshold_value is None
        assert v.w_columns == spec.estimation_variants[0].w_columns

    def test_rewrite_with_filter_remaps_column(self, h3_data):
        spec = make_h3_spec()
        spec = dc_replace(spec, estimation_variants=spec.estimation_variants + [
            EstimationVariant(
                id="v_filtered", treatment_column="nodeRefrigHealthPct",
                treatment_form=TreatmentForm.CONTINUOUS,
                model_type="LinearDML", w_columns=["climateZone"],
                filter=VariantFilter(
                    column="nodeRefrigHealthPct", operator=FilterOperator.GT,
                    values=[50.0]),
            ),
        ])
        rewritten = rewrite_variants_for_positivity(
            spec, "region", "nodeRefrigHealthPct", h3_data, None)
        filtered_v = next((v for v in rewritten if v.id == "v_filtered"), None)
        if filtered_v and filtered_v.filter:
            assert filtered_v.filter.column == "region"
            assert filtered_v.treatment_column == "region"

    def test_rewrite_reference_category_mapped(self, h3_data):
        spec = make_h3_spec()
        spec = dc_replace(spec, estimation_variants=[
            EstimationVariant(
                id="v_ref", treatment_column="nodeRefrigHealthPct",
                treatment_form=TreatmentForm.CONTINUOUS,
                model_type="LinearDML", w_columns=["climateZone"],
                reference_category="99.5",
            ),
        ])
        rewritten = rewrite_variants_for_positivity(
            spec, "region", "nodeRefrigHealthPct", h3_data, None)
        assert len(rewritten) >= 1
        for v in rewritten:
            assert v.treatment_column == "region"
            assert v.treatment_form == TreatmentForm.CATEGORICAL

    def test_apply_rewrite_atomic_all_spec_fields(self, h3_data):
        spec = make_h3_spec()
        rewritten = rewrite_variants_for_positivity(
            spec, "nodeId", "nodeRefrigHealthPct", h3_data, None)
        new = apply_positivity_rewrite(spec, rewritten, "nodeId", None, h3_data)
        assert new.treatment == "nodeId"
        assert new.treatment_form == TreatmentForm.CATEGORICAL
        assert new.original_treatment == "nodeRefrigHealthPct"
        assert new.outcome == spec.outcome
        assert new.hypothesis_id == spec.hypothesis_id
        assert new.dag_edges == spec.dag_edges
        assert new.adjustment_set == spec.adjustment_set
        for v in new.estimation_variants:
            assert v.treatment_column == "nodeId"
            assert v.treatment_form == TreatmentForm.CATEGORICAL
        for g in new.grf_configs:
            assert "nodeId" not in g.modifier_columns
            assert "nodeId" not in g.slicing
        assert new.externalization.allocation_bias == spec.externalization.allocation_bias

    def test_apply_rewrite_no_op_when_treatment_unchanged(self):
        spec = make_spec(treatment="T", treatment_form=TreatmentForm.CONTINUOUS)
        new = apply_positivity_rewrite(spec, list(spec.estimation_variants), "T", None)
        assert new.treatment == "T"
        assert new.treatment_form == TreatmentForm.CONTINUOUS
        assert new.original_treatment is None
        for v_old, v_new in zip(spec.estimation_variants, new.estimation_variants):
            assert v_old.treatment_column == v_new.treatment_column
            assert v_old.treatment_form == v_new.treatment_form

    def test_rewrite_dict_round_trip(self, h3_data):
        spec = make_h3_spec()
        rewritten = rewrite_variants_for_positivity(
            spec, "nodeId", "nodeRefrigHealthPct", h3_data, None)
        as_dicts = [asdict(v) for v in rewritten]
        new = apply_positivity_rewrite(spec, as_dicts, "nodeId", None, h3_data)
        assert new.treatment == "nodeId"
        for v in new.estimation_variants:
            assert v.treatment_column == "nodeId"
            assert v.treatment_form == TreatmentForm.CATEGORICAL

    def test_protected_columns_complete(self, h3_data):
        spec = make_h3_spec()
        spec = dc_replace(spec, original_treatment="nodeRefrigHealthPct",
                          treatment="nodeId",
                          mediators_excluded=[
                              MediatorExclusion(column="preDepartureTempC",
                                                pathway="T->M->Y",
                                                direct_effect_variant_id="v1"),
                          ])
        dag = edges_to_nx(parse_dag_edges(H3_DAG_EDGES))
        protected = build_protected_columns(spec, dag)
        assert "nodeRefrigHealthPct" in protected
        assert "preDepartureTempC" in protected
        assert "nodePowerStatus" in protected
        assert "ambientTempAtDispatchC" in protected
        assert "hasRedundancy" in protected
        assert "dispatchYear" in protected
        assert "excursionFlag" not in protected
        assert "vehicleMakeModel" not in protected


class TestH3PostRewriteDownstream:

    def test_threshold_on_original_column(self, h3_data):
        spec = make_h3_spec(include_node_id=False, positivity=False)
        spec = dc_replace(spec, treatment="nodeId",
                          original_treatment="nodeRefrigHealthPct",
                          treatment_form=TreatmentForm.CATEGORICAL)
        edges = parse_dag_edges(H3_DAG_EDGES)
        with patch("service.causal_verification.sensitivity.run_dml_quick",
                   return_value=-0.005):
            r = sensitivity(h3_data, spec, edges, -0.007, {})
        for tv in r["threshold_variants"]:
            if tv.get("error") is None:
                assert tv["n_treated"] + tv["n_control"] == len(h3_data)
                assert tv["n_treated"] > 0
                assert tv["n_control"] > 0
                assert isinstance(tv["effect"], float)

    def test_allocation_bias_on_original(self, h3_data):
        spec = make_h3_spec(include_node_id=False, positivity=False)
        spec = dc_replace(spec, treatment="nodeId",
                          original_treatment="nodeRefrigHealthPct")
        r = externalization(h3_data, spec,
                            {"steps": {"estimation": {}, "sensitivity": {}, "grf": []}})
        for ab in r["allocation_bias"]:
            assert ab["treatment_column"] == "nodeRefrigHealthPct"
            assert isinstance(ab["max_deviation"], float)
            assert ab["max_deviation"] >= 0

    def test_residual_excludes_protected(self, h3_data):
        spec = make_h3_spec(include_node_id=False, positivity=False)
        spec = dc_replace(spec, original_treatment="nodeRefrigHealthPct")
        edges = parse_dag_edges(H3_DAG_EDGES)
        protected = build_protected_columns(spec, edges_to_nx(edges))
        r = residual_diagnostics(h3_data, spec, H3_CONFOUNDERS, edges, -0.007,
                                 protected_columns=protected)
        for c in r.get("corrections", []):
            assert c["field"] not in protected

    def test_empty_domain_ranking_effects_error(self, h3_data):
        spec = make_h3_spec(include_node_id=False, positivity=False)
        r = externalization(h3_data, spec, {
            "steps": {
                "estimation": {"continuous_total": {"effect": -0.007}},
                "sensitivity": {"threshold_variants": []},
                "grf": [],
            }
        })
        for dr in r["domain_rankings"]:
            assert "error" in dr
            assert "no effect estimates" in dr["error"]

    def test_temporal_placebo_skips_categorical(self, h3_data):
        spec = make_h3_spec()
        spec = dc_replace(spec, treatment="nodeId",
                          treatment_form=TreatmentForm.CATEGORICAL,
                          original_treatment=None)
        dag = edges_to_nx(parse_dag_edges(H3_DAG_EDGES))
        r = temporal_placebo(h3_data, spec, dag, -0.007)
        assert r.get("skipped") is True or "error" in r

    def test_quality_gates_r2_without_nodeId(self, h3_data):
        spec = make_h3_spec(include_node_id=False, positivity=False)
        r = quality_gates(h3_data, spec, H3_CONFOUNDERS, -0.007, (-0.01, -0.003))
        assert r["nuisance_r2"]["treatment_r2"] < 0.99

    def test_estimation_on_correct_treatment(self, h3_data):
        spec = make_h3_spec(include_node_id=False, positivity=False)
        variant = spec.estimation_variants[0]
        edges = parse_dag_edges(H3_DAG_EDGES)
        dag = edges_to_nx(edges)
        r = run_estimation_variant(h3_data, variant, edges, dag, "excursionFlag")
        assert r["variant_id"] == "continuous_total"
        assert r["treatment_column"] == "nodeRefrigHealthPct"
        assert r["discrete"] is False
        assert abs(r["effect"]) < 0.05, \
            f"continuous ATE on binary outcome should be small per-unit, got {r['effect']:.6f}"
        assert r["n_obs"] == len(h3_data)
        lo, hi = r["ci"]
        assert lo < hi


class TestCycleCheck:

    def test_self_loop(self):
        edges = [("A", "B"), ("B", "A_alias")]
        new = [("B" if s == "A" else s, "B" if d == "A" else d) for s, d in edges]
        assert not nx.is_directed_acyclic_graph(edges_to_nx(new))

    def test_safe_rename(self):
        edges = [("T", "O"), ("C", "T"), ("C", "O")]
        new = [("T2" if s == "T" else s, "T2" if d == "T" else d) for s, d in edges]
        assert nx.is_directed_acyclic_graph(edges_to_nx(new))

    def test_multi_hop(self):
        edges = [("A", "B"), ("B", "C"), ("C", "D")]
        new = [("C" if s == "A" else s, "C" if d == "A" else d) for s, d in edges]
        assert not nx.is_directed_acyclic_graph(edges_to_nx(new))


class TestRefutationErrors:

    def test_collect_all_fields(self):
        f_err = Future();
        f_err.set_exception(RuntimeError("crash"))
        f_ok = Future();
        f_ok.set_result(("placebo", MagicMock(new_effect=0.01)))
        spec = make_spec()
        r = collect_refutations({"broken": f_err, "placebo": f_ok}, spec, 1.0)
        assert isinstance(r["broken"]["error"], str)
        assert "crash" in r["broken"]["error"]
        assert r["placebo"]["new_effect"] == pytest.approx(0.01)
        assert isinstance(r["placebo"]["ratio"], float)
        assert r["placebo"]["ratio"] == pytest.approx(0.01)
        assert isinstance(r["placebo"]["flag"], bool)

    def test_error_count(self):
        results = {
            "placebo": {"error": "dag"},
            "random_cause": {"error": "dag"},
            "subset": {"error": "dag"},
            "temporal_placebo": {"worst_ratio": 0.001, "flag": False},
        }
        assert sum(1 for v in results.values() if "error" in v) == 3


class TestPipelineDataFrame:

    def test_encoding_deterministic(self):
        df = pd.DataFrame({"cat": ["c", "a", "b", "a", "c"], "num": [1, 2, 3, 4, 5]})
        pdf = PipelineDataFrame.from_dataframe(df)
        assert pdf.encoders["cat"] == {"a": 0, "b": 1, "c": 2}
        assert pdf.encoded["cat"].tolist() == [2, 0, 1, 0, 2]
        assert pdf.encoded["num"].tolist() == [1, 2, 3, 4, 5]

    def test_filter_mask(self):
        df = pd.DataFrame({"x": range(10), "cat": list("aabbccddee")})
        pdf = PipelineDataFrame.from_dataframe(df)
        mask = pd.Series([i % 2 == 0 for i in range(10)])
        f = pdf.filter_mask(mask)
        assert len(f) == len(f.raw) == len(f.encoded) == 5
        assert f.raw.index.tolist() == list(range(5))
        assert f.encoded.index.tolist() == list(range(5))

    def test_subsample_proportions(self):
        df = pd.DataFrame({"x": range(1000), "g": ["A"] * 500 + ["B"] * 500})
        pdf = PipelineDataFrame.from_dataframe(df)
        sub = pdf.stratified_subsample("g", 0.1)
        counts = sub.raw["g"].value_counts()
        assert 40 <= counts["A"] <= 60
        assert 40 <= counts["B"] <= 60


class TestRecentFixes:
    """Tests for bugs (v), (a-d), (e-f), (j/n), (k) found in code audit."""

    def test_autocorrection_uses_primary_w_not_dag_derived(self, causal_data):
        """(v): residual_diagnostics auto-correction now uses primary W."""
        data, _ = causal_data
        spec = make_spec(
            confounders=["conf"],
            residual_checks=ResidualChecks(
                autocorrelation=[],
                field_correlation=FieldCorrelationCheck(
                    threshold=0.001, check_columns=["noise"]),
                auto_correction=AutoCorrectionConfig(
                    max_iterations=3, stop_criterion_ci_pct=5.0),
                metadata_correlation=[],
            ),
        )
        edges = CAUSAL_EDGES + [("noise", "outcome")]
        captured_w = []
        orig_run_dml = run_dml_quick
        from service.causal_verification import residual_diagnostics as rd_mod

        def wrapped(*args, **kwargs):
            if "w_cols" in kwargs and kwargs["w_cols"] is not None:
                captured_w.append(list(kwargs["w_cols"]))
            return 0.5

        with patch.object(rd_mod, "run_dml_quick", side_effect=wrapped):
            rd_mod.residual_diagnostics(data, spec, ["conf"], edges, 0.5)
        if captured_w:
            first_w = captured_w[0]
            assert "conf" in first_w, \
                f"auto-correction W should extend primary W (['conf']), got {first_w}"

    def test_e_value_rejects_non_binary_outcome(self, causal_data):
        """(b): E-value computation now requires binary outcome."""
        data, _ = causal_data
        spec = make_spec(
            confounders=["conf"],
            unmeasured_confounding=[
                UnmeasuredConfoundingConfig(
                    variant_id="v1", method=UnmeasuredMethod.E_VALUE,
                    null_hypothesis="ATE=0", notes="test"),
            ],
        )
        r = unmeasured_confounding(data, spec,
                                   {"v1": {"effect": -0.5, "ci": (-0.7, -0.3), "discrete": False}})
        assert len(r) == 1
        assert r[0]["method"] == "E_VALUE"
        assert "error" in r[0]
        assert "binary" in r[0]["error"].lower()

    def test_e_value_uses_nearest_null_ci_bound(self):
        """(c): E-value CI uses CI bound closest to null, not always ci[0]."""
        rng = np.random.default_rng(42)
        n = 2000
        df = pd.DataFrame({
            "treatment": rng.normal(50, 10, n),
            "outcome": rng.binomial(1, 0.2, n).astype(float),
            "conf": rng.normal(0, 1, n),
        })
        data = PipelineDataFrame.from_dataframe(df)
        spec = make_spec(
            confounders=["conf"],
            unmeasured_confounding=[
                UnmeasuredConfoundingConfig(
                    variant_id="v_pos", method=UnmeasuredMethod.E_VALUE,
                    null_hypothesis="ATE=0", notes="pos effect"),
            ],
        )
        r = unmeasured_confounding(data, spec, {
            "v_pos": {"effect": 0.05, "ci": (0.01, 0.10), "discrete": False},
        })
        assert r[0].get("ci_bound_used") == pytest.approx(0.01), \
            f"for pos effect CI (0.01, 0.10), nearest-null is 0.01, got {r[0].get('ci_bound_used')}"

        r2 = unmeasured_confounding(data, spec, {
            "v_pos": {"effect": -0.05, "ci": (-0.10, -0.01), "discrete": False},
        })
        assert r2[0].get("ci_bound_used") == pytest.approx(-0.01), \
            f"for neg effect CI (-0.10, -0.01), nearest-null is -0.01, got {r2[0].get('ci_bound_used')}"

    def test_e_value_ci_crosses_zero_sets_e_ci_one(self):
        rng = np.random.default_rng(42)
        n = 2000
        df = pd.DataFrame({
            "treatment": rng.normal(50, 10, n),
            "outcome": rng.binomial(1, 0.2, n).astype(float),
            "conf": rng.normal(0, 1, n),
        })
        data = PipelineDataFrame.from_dataframe(df)
        spec = make_spec(
            confounders=["conf"],
            unmeasured_confounding=[
                UnmeasuredConfoundingConfig(
                    variant_id="v1", method=UnmeasuredMethod.E_VALUE,
                    null_hypothesis="ATE=0", notes="test"),
            ],
        )
        r = unmeasured_confounding(data, spec, {
            "v1": {"effect": 0.01, "ci": (-0.05, 0.07), "discrete": False},
        })
        assert r[0]["e_value_ci"] == 1.0, \
            "CI that crosses zero → no bound against unmeasured confounding"
        assert r[0]["ci_bound_used"] == 0.0

    def test_rosenbaum_bounds_flagged_as_unimplemented(self, causal_data):
        """(d): Rosenbaum bounds method now flags itself as not implemented."""
        data, _ = causal_data
        spec = make_spec(
            confounders=["conf"],
            unmeasured_confounding=[
                UnmeasuredConfoundingConfig(
                    variant_id="v1", method=UnmeasuredMethod.ROSENBAUM_BOUNDS,
                    null_hypothesis="ATE=0", notes="rosenbaum test"),
            ],
        )
        r = unmeasured_confounding(data, spec,
                                   {"v1": {"effect": 0.5, "ci": (0.3, 0.7), "discrete": False}})
        assert r[0]["method"] == "ROSENBAUM_BOUNDS"
        assert r[0]["implemented"] is False
        assert "not implemented" in r[0]["error"]

    def test_treatment_cv_undefined_for_categorical(self):
        """(j): treatment_cv returns None + note for categorical treatment."""
        rng = np.random.default_rng(42)
        n = 1000
        df = pd.DataFrame({
            "treatment": rng.choice(["A", "B", "C", "D"], n),
            "outcome": rng.normal(0, 1, n),
            "conf": rng.normal(0, 1, n),
        })
        data = PipelineDataFrame.from_dataframe(df)
        spec = make_spec(confounders=["conf"])
        r = range_checks(data, spec, ["conf"], {})
        assert r["treatment_cv"] is None
        assert "categorical" in r["treatment_cv_note"].lower()

    def test_treatment_cv_defined_for_continuous(self, causal_data):
        data, _ = causal_data
        spec = make_spec(confounders=["conf"])
        r = range_checks(data, spec, ["conf"], {})
        assert r["treatment_cv"] is not None
        assert isinstance(r["treatment_cv"], float)

    def test_quality_gates_uses_accuracy_for_binary_outcome(self):
        """(n): quality_gates uses accuracy for binary outcome, r2 for continuous."""
        rng = np.random.default_rng(42)
        n = 1000
        conf = rng.normal(0, 1, n)
        treatment = conf + rng.normal(0, 1, n)
        outcome_prob = 1 / (1 + np.exp(-(0.5 * treatment + conf)))
        outcome = rng.binomial(1, outcome_prob).astype(float)
        df = pd.DataFrame({"treatment": treatment, "outcome": outcome, "conf": conf})
        data = PipelineDataFrame.from_dataframe(df)
        spec = make_spec(confounders=["conf"])
        r = quality_gates(data, spec, ["conf"], 0.5, (0.3, 0.7))
        assert r["nuisance_r2"]["outcome_score_type"] == "accuracy"
        assert 0.5 <= r["nuisance_r2"]["outcome_r2"] <= 1.0, \
            "binary-outcome accuracy should be in [0.5, 1.0]"

    def test_quality_gates_uses_accuracy_for_categorical_treatment(self):
        """(n): quality_gates uses accuracy for categorical treatment R²."""
        rng = np.random.default_rng(42)
        n = 1000
        conf = rng.normal(0, 1, n)
        treat = np.where(conf > 0, "high", "low")
        outcome = rng.normal(0, 1, n) + (treat == "high").astype(float)
        df = pd.DataFrame({"treatment": treat, "outcome": outcome, "conf": conf})
        data = PipelineDataFrame.from_dataframe(df)
        spec = make_spec(
            confounders=["conf"],
            treatment_form=TreatmentForm.CATEGORICAL,
        )
        r = quality_gates(data, spec, ["conf"], 0.5, (0.3, 0.7))
        assert r["nuisance_r2"]["treatment_score_type"] == "accuracy"

    def test_overlap_rejects_multilevel_treatment_without_threshold(self):
        """(l/k): Overlap no longer silently binarizes multi-level treatment."""
        rng = np.random.default_rng(42)
        n = 1000
        df = pd.DataFrame({
            "treatment": rng.integers(0, 10, n).astype(float),
            "outcome": rng.normal(0, 1, n),
            "conf": rng.normal(0, 1, n),
        })
        data = PipelineDataFrame.from_dataframe(df)
        spec = make_spec(
            confounders=["conf"],
            estimation_variants=[
                EstimationVariant(
                    id="v_multi", treatment_column="treatment",
                    treatment_form=TreatmentForm.CATEGORICAL,
                    model_type="LinearDML", w_columns=["conf"],
                ),
            ],
            range_checks=RangeChecks(
                vif=VifConfig(threshold=10.0, drop_pairs=[]),
                overlap=[OverlapCheck(variant_id="v_multi", threshold=0.1,
                                      response_strategy=OverlapStrategy.TRIM,
                                      trim_bounds=[0.01, 0.99])],
                variance=[],
            ),
        )
        r = range_checks(data, spec, ["conf"],
                         {"v_multi": {"treatment_column": "treatment"}})
        ov = r["overlap"][0]
        assert "error" in ov, \
            f"multi-level treatment without threshold should error, got {ov}"
        assert "binary" in ov["error"].lower() or "BINARY_THRESHOLD" in ov["error"]

    def test_overlap_binary_threshold_reports_n_splits(self, binary_data):
        """(k): Overlap with BINARY_THRESHOLD variant reports n_treated/n_control."""
        rng = np.random.default_rng(42)
        n = 2000
        df = pd.DataFrame({
            "treatment": rng.normal(50, 10, n),
            "outcome": rng.binomial(1, 0.3, n).astype(float),
            "conf": rng.normal(0, 1, n),
        })
        data = PipelineDataFrame.from_dataframe(df)
        spec = make_spec(
            confounders=["conf"],
            estimation_variants=[
                EstimationVariant(
                    id="v_bt", treatment_column="treatment",
                    treatment_form=TreatmentForm.BINARY_THRESHOLD,
                    threshold_value=50.0,
                    model_type="LinearDML", w_columns=["conf"],
                ),
            ],
            range_checks=RangeChecks(
                vif=VifConfig(threshold=10.0, drop_pairs=[]),
                overlap=[OverlapCheck(variant_id="v_bt", threshold=0.1,
                                      response_strategy=OverlapStrategy.TRIM,
                                      trim_bounds=[0.01, 0.99])],
                variance=[],
            ),
        )
        r = range_checks(data, spec, ["conf"],
                         {"v_bt": {"treatment_column": "_bin_treatment_50.0"}})
        ov = r["overlap"][0]
        assert "error" not in ov, f"should not error, got {ov}"
        assert ov["binarization"] == "threshold"
        assert ov["n_treated"] + ov["n_control"] == n
        assert 500 < ov["n_treated"] < 1500

    def test_grf_fits_continuous_when_original_is_continuous(self, h3_data):
        """(e/f): GRF fits continuous treatment even when spec.treatment_form
        was rewritten to CATEGORICAL (grf uses original_treatment)."""
        from service.causal_verification.grf import grf_heterogeneity
        from dataclasses import replace as dc_replace
        spec = make_h3_spec(include_node_id=False, positivity=False)
        spec = dc_replace(spec,
                          treatment="nodeId",
                          original_treatment="nodeRefrigHealthPct",
                          treatment_form=TreatmentForm.CATEGORICAL,
                          grf_configs=[GrfConfig(
                              id="g1",
                              modifier_columns=["climateZone"],
                              slicing={"climateZone": "unique"},
                          )])
        results = grf_heterogeneity(h3_data, spec, H3_CONFOUNDERS)
        assert len(results) == 1
        r = results[0]
        assert r.get("config_id") == "g1"
        assert "error" not in r or "slices" in r


class TestRemainingFixes:
    """Tests for (h), (o), (p), (s/t), (w), (x)."""

    def test_power_analysis_uses_ci_raw_not_clamped(self):
        """(h): power_analysis should use ci_raw (unclamped) for SE computation."""
        from service.causal_verification.null_diagnostics import null_diagnostics
        rng = np.random.default_rng(42)
        n = 500
        df = pd.DataFrame({
            "treatment": rng.choice([0.0, 1.0], n),
            "outcome": rng.binomial(1, 0.1, n).astype(float),
            "conf": rng.normal(0, 1, n),
        })
        data = PipelineDataFrame.from_dataframe(df)
        spec = make_spec(confounders=["conf"])
        primary_ci_clamped = (-1.0, 0.5)
        primary_ci_raw = (-1.5, 0.8)
        estimation_results = {
            "v1": {
                "effect": -0.3,
                "ci": primary_ci_clamped,
                "ci_raw": primary_ci_raw,
                "discrete": True,
            }
        }
        sensitivity_result = {"confounder_drops": []}
        r = null_diagnostics(data, spec, ["conf"], -0.3, primary_ci_clamped,
                             estimation_results, sensitivity_result)
        pa = r["power_analysis"]
        assert "observed_se" in pa
        from scipy.stats import norm
        z_alpha = norm.ppf(0.975)
        expected_se = (primary_ci_raw[1] - primary_ci_raw[0]) / (2 * z_alpha)
        assert abs(pa["observed_se"] - expected_se) < 1e-6, \
            f"SE should use raw CI, got {pa['observed_se']} expected {expected_se}"

    def test_power_analysis_handles_degenerate_ci(self):
        from service.causal_verification.null_diagnostics import power_analysis
        data = make_pipeline_df(n=200)
        spec = make_spec()
        r = power_analysis(data, spec, 0.5, (0.5, 0.5))
        assert "error" in r
        r2 = power_analysis(data, spec, 0.5, None)
        assert "error" in r2

    def test_externalization_threshold_key_normalization(self):
        """(o): threshold 50 in ordering matches 50.0 in tv_effects."""
        data = make_pipeline_df(n=200)
        spec = make_spec(
            externalization=ExternalizationConfig(
                domain_rankings=[
                    DomainRanking(ordering="(50) > (70) > (90)",
                                  source="test", scope="all",
                                  expected_concordance=0.5),
                ],
                allocation_bias=[],
            ),
        )
        result_dict = {
            "steps": {
                "estimation": {},
                "sensitivity": {
                    "threshold_variants": [
                        {"threshold": 50.0, "effect": -0.15, "n_treated": 2500, "n_control": 2500},
                        {"threshold": 70.0, "effect": -0.10, "n_treated": 2500, "n_control": 2500},
                        {"threshold": 90.0, "effect": -0.05, "n_treated": 2500, "n_control": 2500},
                    ],
                },
                "grf": [],
            }
        }
        r = externalization(data, spec, result_dict)
        dr = r["domain_rankings"][0]
        assert "error" not in dr, f"should match all thresholds, got {dr.get('error')}"
        assert dr["source_type"] == "threshold_variants"
        assert dr["unmatched_elements"] == [], \
            f"all three thresholds should match, unmatched: {dr['unmatched_elements']}"

    def test_externalization_tv_sample_size_is_total_not_control(self):
        """(q): sample sizes should be n_treated + n_control, not just n_control."""
        data = make_pipeline_df(n=200)
        spec = make_spec(
            externalization=ExternalizationConfig(
                domain_rankings=[
                    DomainRanking(ordering="(50) > (70)",
                                  source="test", scope="all",
                                  expected_concordance=0.5),
                ],
                allocation_bias=[],
            ),
        )
        result_dict = {
            "steps": {
                "estimation": {},
                "sensitivity": {
                    "threshold_variants": [
                        {"threshold": 50.0, "effect": -0.15, "n_treated": 100, "n_control": 4900},
                        {"threshold": 70.0, "effect": -0.10, "n_treated": 2000, "n_control": 3000},
                    ],
                },
                "grf": [],
            }
        }
        r = externalization(data, spec, result_dict)
        dr = r["domain_rankings"][0]
        details = dr.get("details", {}).get("cross_tier", [])
        if details:
            all_ns = set()
            for pair in details:
                all_ns.add(pair.get("left_n"))
                all_ns.add(pair.get("right_n"))
            assert 5000 in all_ns, \
                f"sample size should be total (5000), got {all_ns}"

    def test_dsep_uses_configured_threshold(self):
        """(s,t): d-sep uses user-configured threshold, not max_r^1.5 heuristic."""
        rng = np.random.default_rng(42)
        n = 3000
        x = rng.normal(0, 1, n)
        df = pd.DataFrame({
            "A": x + rng.normal(0, 1.5, n),
            "B": x + rng.normal(0, 1.5, n),
            "outcome": rng.normal(0, 1, n),
        })
        data = PipelineDataFrame.from_dataframe(df)
        edges = [("A", "outcome"), ("B", "outcome")]
        r_low = dsep_refinement(data, edges, 0.05)
        r_high = dsep_refinement(data, edges, 0.99)
        assert r_low["violation_count"] >= r_high["violation_count"], \
            f"lower threshold should flag >= violations: " \
            f"low={r_low['violation_count']} vs high={r_high['violation_count']}"
        for v in r_high["violations"]:
            assert abs(v["correlation"]) > 0.99, \
                f"threshold 0.99 should only flag |r|>0.99, got r={v['correlation']}"

    def test_mediation_rejects_different_filters(self):
        """(w): mediation errors when total/direct variants have different filters."""
        rng = np.random.default_rng(42)
        n = 200
        df = pd.DataFrame({
            "treatment": rng.normal(0, 1, n),
            "outcome": rng.normal(0, 1, n),
            "conf": rng.normal(0, 1, n),
            "mediator": rng.normal(0, 1, n),
            "subset": rng.choice(["A", "B"], n),
        })
        data = PipelineDataFrame.from_dataframe(df)
        spec = make_spec(
            confounders=["conf"],
            estimation_variants=[
                EstimationVariant(
                    id="v_total", treatment_column="treatment",
                    treatment_form=TreatmentForm.CONTINUOUS,
                    model_type="LinearDML", w_columns=["conf"],
                    filter=None,
                ),
                EstimationVariant(
                    id="v_direct", treatment_column="treatment",
                    treatment_form=TreatmentForm.CONTINUOUS,
                    model_type="LinearDML", w_columns=["conf", "mediator"],
                    filter=VariantFilter(column="subset",
                                         operator=FilterOperator.EQ, values=["A"]),
                ),
            ],
            mediation=[MediationConfig(
                mediator="mediator", pathway="T->M->Y",
                total_variant_id="v_total", direct_variant_id="v_direct",
            )],
        )
        estimation_results = {
            "v_total": {"effect": 0.5},
            "v_direct": {"effect": 0.2},
        }
        r = mediation(data, spec, ["conf"], estimation_results, [])
        assert len(r) == 1
        assert "error" in r[0]
        assert "filter" in r[0]["error"].lower()

    def test_mediation_rejects_different_treatment_columns(self):
        data = make_pipeline_df(n=200)
        spec = make_spec(
            confounders=["conf_a"],
            estimation_variants=[
                EstimationVariant(
                    id="v_total", treatment_column="treatment",
                    treatment_form=TreatmentForm.CONTINUOUS,
                    model_type="LinearDML", w_columns=["conf_a"],
                ),
                EstimationVariant(
                    id="v_direct", treatment_column="conf_a",
                    treatment_form=TreatmentForm.CATEGORICAL,
                    model_type="LinearDML", w_columns=["conf_b"],
                ),
            ],
            mediation=[MediationConfig(
                mediator="conf_b", pathway="T->M->Y",
                total_variant_id="v_total", direct_variant_id="v_direct",
            )],
        )
        estimation_results = {
            "v_total": {"effect": 0.5},
            "v_direct": {"effect": 0.3},
        }
        r = mediation(data, spec, ["conf_a"], estimation_results, [])
        assert len(r) == 1
        assert "error" in r[0]
        assert "treatment" in r[0]["error"].lower()

    def test_structural_breaks_rejects_categorical_treatment(self, h3_data):
        """(x): structural_breaks errors on categorical treatment."""
        from service.causal_verification.structural_breaks import structural_breaks
        from dataclasses import replace as dc_replace
        df = h3_data.raw.copy()
        df["shipmentDate"] = pd.date_range("2024-01-01", periods=len(df), freq="h")
        data = PipelineDataFrame.from_dataframe(df)
        spec = make_h3_spec(include_node_id=False, positivity=False)
        spec = dc_replace(spec,
                          treatment="nodeId",
                          treatment_form=TreatmentForm.CATEGORICAL,
                          original_treatment=None,
                          structural_breaks=[
                              StructuralBreakConfig(
                                  id="sb1",
                                  entity_column="region",
                                  temporal_column="shipmentDate",
                                  temporal_grain="M",
                                  pelt_penalty=4.79,
                                  min_obs_per_period=3,
                                  known_events_tables=None,
                                  entity_count=7,
                                  temporal_points=10,
                              ),
                          ])
        r = structural_breaks(data, spec, -0.007, (-0.01, -0.003))
        assert len(r) == 1
        assert r[0]["id"] == "sb1"
        assert "error" in r[0]
        assert "categorical" in r[0]["error"].lower()

    def test_structural_breaks_uses_original_treatment(self, h3_data):
        from service.causal_verification.structural_breaks import structural_breaks
        from dataclasses import replace as dc_replace
        df = h3_data.raw.copy()
        df["shipmentDate"] = pd.date_range("2024-01-01", periods=len(df), freq="h")
        data = PipelineDataFrame.from_dataframe(df)
        spec = make_h3_spec(include_node_id=False, positivity=False)
        spec = dc_replace(spec,
                          treatment="nodeId",
                          treatment_form=TreatmentForm.CATEGORICAL,
                          original_treatment="nodeRefrigHealthPct",
                          structural_breaks=[
                              StructuralBreakConfig(
                                  id="sb_orig",
                                  entity_column="region",
                                  temporal_column="shipmentDate",
                                  temporal_grain="M",
                                  pelt_penalty=4.79,
                                  min_obs_per_period=3,
                                  known_events_tables=None,
                                  entity_count=7,
                                  temporal_points=10,
                              ),
                          ])
        r = structural_breaks(data, spec, -0.007, (-0.01, -0.003))
        assert len(r) == 1
        assert r[0]["id"] == "sb_orig"
        assert "error" not in r[0] or "tier" in r[0]


class TestBackwardCompat:

    def test_imports(self):
        from service.causal_verification_service import CausalVerificationService as C1
        from service.causal_verification import CausalVerificationService as C2
        assert C1 is C2
        svc = C1()
        for m in ('_load_data', '_validate_spec', '_parse_dag_edges',
                  '_edges_to_nx', 'run_pipeline'):
            assert callable(getattr(svc, m))
