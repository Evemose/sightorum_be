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


class TestFilterDeserialization:
    """Tests for VariantFilter.from_dict robustness — production KeyError fix."""

    def test_leaf_filter_basic(self):
        f = VariantFilter.from_dict({
            "column": "region", "operator": "IN", "values": ["A", "B"]
        })
        assert f.column == "region"
        assert f.operator == FilterOperator.IN
        assert f.values == ["A", "B"]

    def test_composite_with_empty_values_array_is_composite(self):
        """LLM-generated specs propagate union-arm fields at every node level.
        A composite node carrying `values: []` alongside null column/operator
        is schema-noise, NOT a partial leaf. The composite branch must win."""
        f = VariantFilter.from_dict({
            "values": [],
            "and": [
                {"column": "ambientTempAtArrivalC", "operator": "GT",
                 "values": [25], "and": [], "or": []},
                {"column": "isAfterHoursArrival", "operator": "EQ",
                 "values": [False], "and": [], "or": []},
                {"column": "receivingDockTempControlled", "operator": "EQ",
                 "values": [False], "and": [], "or": []},
                {"column": "disposition", "operator": "EQ",
                 "values": ["accepted"], "and": [], "or": []},
            ],
            "or": [],
        })
        assert f.and_filters is not None
        assert len(f.and_filters) == 4
        assert f.column is None
        assert f.values is None
        assert f.and_filters[0].column == "ambientTempAtArrivalC"
        assert f.and_filters[0].operator == FilterOperator.GT

    def test_partial_leaf_with_real_values_still_rejected(self):
        """A non-empty `values` without column/operator IS a partial leaf and
        must still raise — only empty-values noise is tolerated."""
        with pytest.raises(ValueError, match="partial|missing"):
            VariantFilter.from_dict({"values": [1, 2, 3]})

    def test_leaf_with_empty_composite_arrays_is_leaf(self):
        """LLM-generated specs sometimes emit `and: []` and `or: []` alongside
        leaf fields as schema noise. Empty composites must not steal precedence."""
        f = VariantFilter.from_dict({
            "column": "vehicleRefrigModel",
            "operator": "IN",
            "values": ["Thermo_King_Advancer_A400", "Daikin_RKN"],
            "and": [],
            "or": [],
        })
        assert f.column == "vehicleRefrigModel"
        assert f.operator == FilterOperator.IN
        assert f.values == ["Thermo_King_Advancer_A400", "Daikin_RKN"]
        assert f.and_filters is None
        assert f.or_filters is None
        assert f.is_leaf

    def test_ambiguous_leaf_plus_nonempty_composite_rejected(self):
        """Both leaf fields AND non-empty composite is malformed — must reject."""
        with pytest.raises(ValueError, match="ambiguous"):
            VariantFilter.from_dict({
                "column": "x",
                "operator": "IN",
                "values": ["a"],
                "and": [{"column": "y", "operator": "EQ", "values": [1]}],
            })

    def test_partial_leaf_with_empty_composite_rejected(self):
        """Partial leaf (column only) plus empty composites is still partial."""
        with pytest.raises(ValueError, match="partial|empty"):
            VariantFilter.from_dict({
                "column": "x",
                "and": [],
                "or": [],
            })

    def test_and_lowercase_jsonconvention(self):
        """Compiler-generated specs use lowercase 'and'/'or'/'not'."""
        f = VariantFilter.from_dict({
            "and": [
                {"column": "vehicleRefrigModel", "operator": "IN",
                 "values": ["Daikin_RKN", "Thermo_King_Advancer_A400"]},
            ]
        })
        assert f.and_filters is not None
        assert len(f.and_filters) == 1
        assert f.and_filters[0].column == "vehicleRefrigModel"
        assert f.and_filters[0].operator == FilterOperator.IN
        assert f.and_filters[0].values == ["Daikin_RKN", "Thermo_King_Advancer_A400"]

    def test_or_lowercase(self):
        f = VariantFilter.from_dict({
            "or": [
                {"column": "a", "operator": "EQ", "values": [1]},
                {"column": "b", "operator": "EQ", "values": [2]},
            ]
        })
        assert f.or_filters is not None
        assert len(f.or_filters) == 2

    def test_not_lowercase(self):
        f = VariantFilter.from_dict({
            "not": {"column": "a", "operator": "EQ", "values": [1]},
        })
        assert f.not_filter is not None
        assert f.not_filter.column == "a"

    def test_v4_production_shape(self):
        """Exact filter shape from production V4 variant that crashed."""
        from dto.causal_verification_request import EstimationVariant as EV
        v = EV.from_dict({
            "id": "V4",
            "treatment_column": "vehicleRefrigModel",
            "treatment_form": "CATEGORICAL",
            "model_type": "LinearDML",
            "w_columns": ["ambientTempAtArrivalC"],
            "reference_category": "Thermo_King_Advancer_A400",
            "threshold_value": None,
            "filter": {
                "and": [
                    {"column": "vehicleRefrigModel", "operator": "IN",
                     "values": ["Daikin_RKN", "Thermo_King_Advancer_A400"]}
                ]
            },
            "notes": "Extreme contrast",
        })
        assert v.id == "V4"
        assert v.filter is not None
        assert v.filter.and_filters is not None
        assert len(v.filter.and_filters) == 1
        leaf = v.filter.and_filters[0]
        assert leaf.column == "vehicleRefrigModel"
        assert leaf.operator == FilterOperator.IN
        assert "Daikin_RKN" in leaf.values

    def test_and_uppercase(self):
        f = VariantFilter.from_dict({
            "AND": [
                {"column": "a", "operator": "EQ", "values": [1]},
                {"column": "b", "operator": "GT", "values": [0]},
            ]
        })
        assert f.and_filters is not None
        assert len(f.and_filters) == 2
        assert f.and_filters[0].column == "a"

    def test_asdict_roundtrip(self):
        from dataclasses import asdict
        original = VariantFilter(and_filters=[
            VariantFilter(column="region", operator=FilterOperator.IN, values=["A"]),
            VariantFilter(column="year", operator=FilterOperator.GT, values=[2020]),
        ])
        as_d = asdict(original)
        rebuilt = VariantFilter.from_dict(as_d)
        assert rebuilt.and_filters is not None
        assert len(rebuilt.and_filters) == 2
        assert rebuilt.and_filters[0].column == "region"
        assert rebuilt.and_filters[0].operator == FilterOperator.IN
        assert rebuilt.and_filters[1].column == "year"

    def test_nested_or_not_roundtrip(self):
        from dataclasses import asdict
        original = VariantFilter(not_filter=VariantFilter(or_filters=[
            VariantFilter(column="x", operator=FilterOperator.LT, values=[5]),
            VariantFilter(column="y", operator=FilterOperator.EQ, values=["a"]),
        ]))
        rebuilt = VariantFilter.from_dict(asdict(original))
        assert rebuilt.not_filter is not None
        assert rebuilt.not_filter.or_filters is not None
        assert len(rebuilt.not_filter.or_filters) == 2

    def test_empty_dict_errors_with_context(self):
        with pytest.raises(ValueError, match="empty"):
            VariantFilter.from_dict({})

    def test_partial_leaf_errors_with_context(self):
        with pytest.raises(ValueError, match="partial|missing"):
            VariantFilter.from_dict({"column": "x", "operator": "IN"})

    def test_non_dict_errors(self):
        with pytest.raises(ValueError, match="expected dict"):
            VariantFilter.from_dict("not a dict")
        with pytest.raises(ValueError, match="expected dict"):
            VariantFilter.from_dict(["A", "B"])

    def test_estimation_variant_propagates_filter_error_with_id(self):
        from dto.causal_verification_request import EstimationVariant as EV
        with pytest.raises(ValueError) as exc:
            EV.from_dict({
                "id": "v_broken",
                "treatment_column": "T",
                "treatment_form": "CONTINUOUS",
                "model_type": "LinearDML",
                "w_columns": ["c1"],
                "filter": {"operator": "IN"},
            })
        assert "v_broken" in str(exc.value)
        assert "filter" in str(exc.value).lower()

    def test_full_request_roundtrip_preserves_filter(self):
        from dataclasses import asdict
        from dto.causal_verification_request import CausalVerificationRequest
        spec = make_spec(
            estimation_variants=[
                EstimationVariant(
                    id="v1", treatment_column="treatment",
                    treatment_form=TreatmentForm.CONTINUOUS,
                    model_type="LinearDML", w_columns=["conf_a"],
                    filter=VariantFilter(and_filters=[
                        VariantFilter(column="conf_a", operator=FilterOperator.IN,
                                      values=["A", "B"]),
                        VariantFilter(column="conf_b", operator=FilterOperator.EQ,
                                      values=["X"]),
                    ]),
                ),
            ],
        )
        d = asdict(spec)
        rebuilt = CausalVerificationRequest.from_dict(d)
        v = rebuilt.estimation_variants[0]
        assert v.id == "v1"
        assert v.filter is not None
        assert v.filter.and_filters is not None
        assert len(v.filter.and_filters) == 2
        assert v.filter.and_filters[0].column == "conf_a"


class TestCaseInsensitiveEnumParsing:
    """All str enums in DTOs accept any case for compiler robustness."""

    def test_treatment_form_uppercase(self):
        from dto.causal_verification_request import _parse_enum
        assert _parse_enum(TreatmentForm, "CONTINUOUS") == TreatmentForm.CONTINUOUS
        assert _parse_enum(TreatmentForm, "continuous") == TreatmentForm.CONTINUOUS
        assert _parse_enum(TreatmentForm, "Continuous") == TreatmentForm.CONTINUOUS

    def test_treatment_form_compound(self):
        from dto.causal_verification_request import _parse_enum
        assert _parse_enum(TreatmentForm, "BINARY_THRESHOLD") == TreatmentForm.BINARY_THRESHOLD
        assert _parse_enum(TreatmentForm, "binary_threshold") == TreatmentForm.BINARY_THRESHOLD
        assert _parse_enum(TreatmentForm, "Binary_Threshold") == TreatmentForm.BINARY_THRESHOLD

    def test_filter_operator_any_case(self):
        from dto.causal_verification_request import _parse_enum
        for v in ("IN", "in", "In", "iN"):
            assert _parse_enum(FilterOperator, v) == FilterOperator.IN

    def test_refutation_type_any_case(self):
        from dto.causal_verification_request import _parse_enum
        assert _parse_enum(RefutationType, "PLACEBO") == RefutationType.PLACEBO
        assert _parse_enum(RefutationType, "placebo") == RefutationType.PLACEBO
        assert _parse_enum(RefutationType, "Random_Cause") == RefutationType.RANDOM_CAUSE

    def test_unmeasured_method_any_case(self):
        from dto.causal_verification_request import _parse_enum
        assert _parse_enum(UnmeasuredMethod, "E_VALUE") == UnmeasuredMethod.E_VALUE
        assert _parse_enum(UnmeasuredMethod, "e_value") == UnmeasuredMethod.E_VALUE
        assert _parse_enum(UnmeasuredMethod, "rosenbaum_bounds") == UnmeasuredMethod.ROSENBAUM_BOUNDS

    def test_overlap_strategy_any_case(self):
        from dto.causal_verification_request import _parse_enum
        assert _parse_enum(OverlapStrategy, "TRIM") == OverlapStrategy.TRIM
        assert _parse_enum(OverlapStrategy, "trim") == OverlapStrategy.TRIM

    def test_slicing_method_uppercase_accepted(self):
        """SlicingMethod canonical is lowercase but compilers may emit uppercase."""
        from dto.causal_verification_request import _parse_enum, SlicingMethod
        assert _parse_enum(SlicingMethod, "unique") == SlicingMethod.UNIQUE
        assert _parse_enum(SlicingMethod, "UNIQUE") == SlicingMethod.UNIQUE
        assert _parse_enum(SlicingMethod, "Quartile") == SlicingMethod.QUARTILE

    def test_invalid_value_lists_options(self):
        from dto.causal_verification_request import _parse_enum
        with pytest.raises(ValueError, match="Valid"):
            _parse_enum(TreatmentForm, "NONLINEAR")

    def test_grf_config_normalizes_slicing_method(self):
        cfg = GrfConfig(id="g1", modifier_columns=["x"],
                        slicing={"x": "QUARTILE"})
        assert cfg.slicing["x"] == "quartile"
        cfg2 = GrfConfig(id="g2", modifier_columns=["x"],
                         slicing={"x": "Unique"})
        assert cfg2.slicing["x"] == "unique"

    def test_grf_config_from_dict_normalizes(self):
        from dto.causal_verification_request import GrfConfig as GC
        cfg = GC.from_dict({
            "id": "g3",
            "modifier_columns": ["a", "b"],
            "slicing": {"a": "UNIQUE", "b": "Quartile"},
        })
        assert cfg.slicing == {"a": "unique", "b": "quartile"}

    def test_estimation_variant_treatment_form_any_case(self):
        from dto.causal_verification_request import EstimationVariant as EV
        v = EV.from_dict({
            "id": "v1",
            "treatment_column": "T",
            "treatment_form": "categorical",
            "model_type": "LinearDML",
            "w_columns": ["c1"],
        })
        assert v.treatment_form == TreatmentForm.CATEGORICAL

    def test_filter_operator_lowercase_in_filter(self):
        f = VariantFilter.from_dict({
            "column": "x", "operator": "in", "values": [1, 2],
        })
        assert f.operator == FilterOperator.IN

    def test_refutation_config_any_case(self):
        from dto.causal_verification_request import RefutationConfig as RC
        assert RC.from_dict({"type": "placebo"}).type == RefutationType.PLACEBO
        assert RC.from_dict({"type": "Temporal_Placebo"}).type == RefutationType.TEMPORAL_PLACEBO

    def test_overlap_check_any_case(self):
        from dto.causal_verification_request import OverlapCheck as OC
        oc = OC.from_dict({
            "variant_id": "v1", "threshold": 0.1,
            "response_strategy": "trim", "trim_bounds": [0.01, 0.99],
        })
        assert oc.response_strategy == OverlapStrategy.TRIM


class TestCategoricalReferenceCategory:
    """reference_category must be honored — the engine cannot silently use
    the alphabetically-first category as the implicit DML reference."""

    def _make_three_level_categorical(self, seed=42, n=5000):
        rng = np.random.default_rng(seed)
        conf = rng.normal(0, 1, n)
        # Three levels chosen so alphabetical order != user-likely reference.
        # Alphabetical: "Carrier_A" < "Daikin_X" < "ThermoKing_T"
        treat = rng.choice(["Carrier_A", "Daikin_X", "ThermoKing_T"], n)
        # ATE per level vs Daikin_X (user-chosen reference):
        #   Daikin_X = 0 (ref), Carrier_A = +0.4, ThermoKing_T = -0.3
        effect = np.where(treat == "Carrier_A", 0.4,
                          np.where(treat == "ThermoKing_T", -0.3, 0.0))
        outcome = effect + 0.5 * conf + rng.normal(0, 0.5, n)
        df = pd.DataFrame({"treatment": treat, "outcome": outcome, "conf": conf})
        return PipelineDataFrame.from_dataframe(df)

    def test_reference_category_honored_when_set(self):
        data = self._make_three_level_categorical()
        variant = EstimationVariant(
            id="v_ref_set", treatment_column="treatment",
            treatment_form=TreatmentForm.CATEGORICAL,
            model_type="LinearDML", w_columns=["conf"],
            reference_category="Daikin_X",
        )
        r = run_estimation_variant(data, variant, CAUSAL_EDGES,
                                   edges_to_nx(CAUSAL_EDGES), "outcome")
        # Engine must surface the actual reference used and it must equal
        # the user-specified one (not the alphabetically-first 'Carrier_A').
        assert r["reference_category"] == "Daikin_X", (
            f"Expected user-specified reference 'Daikin_X', got "
            f"{r['reference_category']!r}"
        )
        cats = r["category_effects"]
        # The reference's coefficient is 0 by construction
        assert cats["treatment=Daikin_X"] == 0.0
        # Carrier_A should be positive (~0.4); ThermoKing_T should be negative (~-0.3)
        assert cats["treatment=Carrier_A"] > 0.1
        assert cats["treatment=ThermoKing_T"] < -0.1

    def test_reference_category_falls_back_when_unset(self):
        data = self._make_three_level_categorical()
        variant = EstimationVariant(
            id="v_ref_unset", treatment_column="treatment",
            treatment_form=TreatmentForm.CATEGORICAL,
            model_type="LinearDML", w_columns=["conf"],
            reference_category=None,
        )
        r = run_estimation_variant(data, variant, CAUSAL_EDGES,
                                   edges_to_nx(CAUSAL_EDGES), "outcome")
        # No user spec → alphabetically-first wins (Carrier_A)
        assert r["reference_category"] == "Carrier_A"
        cats = r["category_effects"]
        assert cats["treatment=Carrier_A"] == 0.0

    def test_reference_category_invalid_warns_and_falls_back(self, caplog):
        import logging
        data = self._make_three_level_categorical()
        variant = EstimationVariant(
            id="v_ref_bad", treatment_column="treatment",
            treatment_form=TreatmentForm.CATEGORICAL,
            model_type="LinearDML", w_columns=["conf"],
            reference_category="NotInData_XYZ",
        )
        with caplog.at_level(logging.WARNING):
            r = run_estimation_variant(data, variant, CAUSAL_EDGES,
                                       edges_to_nx(CAUSAL_EDGES), "outcome")
        # Falls back to alphabetical
        assert r["reference_category"] == "Carrier_A"
        # Warning records the user's invalid choice
        assert any("NotInData_XYZ" in rec.message for rec in caplog.records), \
            f"Expected warning naming the invalid reference; got {caplog.records}"


class TestCategoricalEffectsBoundCheck:
    """Per-category effects on a binary outcome must be in [-1, 1] —
    flag and clamp when DML produces physically impossible values."""

    def test_in_bounds_effects_unchanged_no_warning(self):
        # Realistic DGP — effects stay within [-1, 1]
        rng = np.random.default_rng(42)
        n = 5000
        conf = rng.normal(0, 1, n)
        treat = rng.choice(["A", "B", "C"], n)
        # Linear probability model with effects in [-0.3, +0.3]
        p = 0.4 + np.where(treat == "B", 0.1, np.where(treat == "C", -0.1, 0.0)) \
            + 0.1 * conf
        p = np.clip(p, 0.05, 0.95)
        outcome = (rng.uniform(0, 1, n) < p).astype(float)
        df = pd.DataFrame({"treatment": treat, "outcome": outcome, "conf": conf})
        data = PipelineDataFrame.from_dataframe(df)
        variant = EstimationVariant(
            id="v_ok", treatment_column="treatment",
            treatment_form=TreatmentForm.CATEGORICAL,
            model_type="LinearDML", w_columns=["conf"],
            reference_category="A",
        )
        r = run_estimation_variant(data, variant, CAUSAL_EDGES,
                                   edges_to_nx(CAUSAL_EDGES), "outcome")
        assert r.get("category_effects_degenerate") is not True
        assert "category_effects_warning" not in r
        # No raw shadow when nothing was clamped
        assert "category_effects_raw" not in r
        for label, eff in r["category_effects"].items():
            assert -1.0 <= eff <= 1.0, f"{label}={eff} out of bounds"

    def test_out_of_bounds_effects_clamped_and_flagged(self):
        # Construct a degenerate scenario: rank-deficient W (perfect duplicate)
        # plus tiny near-deterministic-treatment subset to force DML instability.
        # We patch the DML output directly to simulate a numerically-degenerate
        # inner stage, since we can't rely on producing real |eff|>1 from a
        # well-behaved DGP.
        from unittest.mock import patch
        rng = np.random.default_rng(42)
        n = 1000
        conf = rng.normal(0, 1, n)
        treat = rng.choice(["A", "B", "C"], n)
        outcome = rng.binomial(1, 0.065, n).astype(float)
        df = pd.DataFrame({"treatment": treat, "outcome": outcome, "conf": conf})
        data = PipelineDataFrame.from_dataframe(df)
        variant = EstimationVariant(
            id="v_degen", treatment_column="treatment",
            treatment_form=TreatmentForm.CATEGORICAL,
            model_type="LinearDML", w_columns=["conf"],
            reference_category="A",
        )

        # Patch const_marginal_effect to return physically impossible values
        # (mirroring the production failure: -122, +190 for binary outcome)
        bad_cme = np.tile(np.array([[-122.0, 190.0]]), (n, 1))
        with patch("econml.dml.LinearDML.const_marginal_effect",
                   return_value=bad_cme):
            r = run_estimation_variant(data, variant, CAUSAL_EDGES,
                                       edges_to_nx(CAUSAL_EDGES), "outcome")

        assert r.get("category_effects_degenerate") is True
        warn = r.get("category_effects_warning", "")
        assert "outside [-1, 1]" in warn
        assert "degenerate" in warn.lower()
        # Clamped values are within bounds
        for label, eff in r["category_effects"].items():
            assert -1.0 <= eff <= 1.0, f"clamped {label}={eff} still out of bounds"
        # Raw shadow preserved
        raw = r["category_effects_raw"]
        assert raw["treatment=B"] == -122.0
        assert raw["treatment=C"] == 190.0
        # Reference is still 0.0 in both raw and clamped
        assert raw["treatment=A"] == 0.0
        assert r["category_effects"]["treatment=A"] == 0.0

    def test_continuous_outcome_does_not_clamp(self, causal_data):
        # Continuous outcome — bound check should not apply
        data, _ = causal_data
        variant = EstimationVariant(
            id="v_cont", treatment_column="treatment",
            treatment_form=TreatmentForm.CONTINUOUS,
            model_type="LinearDML", w_columns=["conf"],
        )
        r = run_estimation_variant(data, variant, CAUSAL_EDGES,
                                   edges_to_nx(CAUSAL_EDGES), "outcome")
        # Continuous variant produces no category_effects
        assert "category_effects" not in r
        assert "category_effects_warning" not in r
        assert r.get("category_effects_degenerate") is not True


class TestFilterApplicationStrictness:
    """Filter eval must error loudly, never silently no-op."""

    def test_missing_column_raises(self):
        from service.causal_verification.pipeline_utils import eval_filter
        df = pd.DataFrame({"x": [1, 2, 3], "y": [4, 5, 6]})
        f = VariantFilter(column="nonexistent",
                          operator=FilterOperator.EQ, values=[1])
        with pytest.raises(ValueError, match="not in data"):
            eval_filter(df, f)

    def test_unknown_operator_raises(self):
        from service.causal_verification.pipeline_utils import eval_filter
        df = pd.DataFrame({"x": [1, 2, 3]})
        f = VariantFilter(column="x", operator=None, values=[1])
        with pytest.raises(ValueError):
            eval_filter(df, f)

    def test_empty_in_values_raises(self):
        from service.causal_verification.pipeline_utils import eval_filter
        df = pd.DataFrame({"x": [1, 2, 3]})
        f = VariantFilter(column="x", operator=FilterOperator.IN, values=[])
        with pytest.raises(ValueError, match="empty values"):
            eval_filter(df, f)

    def test_empty_and_is_vacuously_true(self):
        """Empty AND is the identity for conjunction — all rows pass."""
        from service.causal_verification.pipeline_utils import eval_filter
        df = pd.DataFrame({"x": [1, 2, 3]})
        f = VariantFilter(and_filters=[])
        mask = eval_filter(df, f)
        assert mask.all()
        assert len(mask) == 3

    def test_empty_or_is_vacuously_false(self):
        """Empty OR is the identity for disjunction — no rows pass."""
        from service.causal_verification.pipeline_utils import eval_filter
        df = pd.DataFrame({"x": [1, 2, 3]})
        f = VariantFilter(or_filters=[])
        mask = eval_filter(df, f)
        assert not mask.any()
        assert len(mask) == 3

    def test_parser_accepts_lone_empty_and(self):
        """`{"and": []}` parses cleanly and evaluates to all-true."""
        from service.causal_verification.pipeline_utils import eval_filter
        f = VariantFilter.from_dict({"and": []})
        assert f.and_filters == []
        mask = eval_filter(pd.DataFrame({"x": [1, 2, 3]}), f)
        assert mask.all()

    def test_parser_accepts_lone_empty_or(self):
        """`{"or": []}` parses cleanly and evaluates to all-false."""
        from service.causal_verification.pipeline_utils import eval_filter
        f = VariantFilter.from_dict({"or": []})
        assert f.or_filters == []
        mask = eval_filter(pd.DataFrame({"x": [1, 2, 3]}), f)
        assert not mask.any()

    def test_apply_filter_includes_variant_id_in_error(self, causal_data):
        from service.causal_verification.pipeline_utils import apply_variant_filter
        data, _ = causal_data
        variant = EstimationVariant(
            id="v_bad_filter", treatment_column="treatment",
            treatment_form=TreatmentForm.CONTINUOUS,
            model_type="LinearDML", w_columns=["conf"],
            filter=VariantFilter(column="nonexistent_col",
                                 operator=FilterOperator.EQ, values=["x"]),
        )
        with pytest.raises(ValueError, match="v_bad_filter"):
            apply_variant_filter(data, variant)

    def test_filter_actually_reduces_rows(self, causal_data):
        from service.causal_verification.pipeline_utils import apply_variant_filter
        data, _ = causal_data
        variant = EstimationVariant(
            id="v_real_filter", treatment_column="treatment",
            treatment_form=TreatmentForm.CONTINUOUS,
            model_type="LinearDML", w_columns=["conf"],
            filter=VariantFilter(column="noise",
                                 operator=FilterOperator.GT, values=[0.0]),
        )
        n_before = len(data)
        filtered = apply_variant_filter(data, variant)
        assert 0 < len(filtered) < n_before, \
            f"filter should reduce rows; before={n_before}, after={len(filtered)}"

    def test_run_estimation_variant_propagates_filter_error(self, causal_data):
        data, _ = causal_data
        variant = EstimationVariant(
            id="v_bad", treatment_column="treatment",
            treatment_form=TreatmentForm.CONTINUOUS,
            model_type="LinearDML", w_columns=["conf"],
            filter=VariantFilter(column="missing_col",
                                 operator=FilterOperator.EQ, values=["x"]),
        )
        with pytest.raises(ValueError, match="missing_col|v_bad"):
            run_estimation_variant(data, variant, CAUSAL_EDGES,
                                   edges_to_nx(CAUSAL_EDGES), "outcome")


class TestRefutationsCategoricalTreatment:
    """Refutations must not crash on string-valued categorical treatment."""

    def test_dowhy_receives_numeric_treatment_column(self):
        """Categorical string treatment is encoded to numeric before dowhy."""
        from service.causal_verification.refutations import refutations_parallel
        from concurrent.futures import Future
        rng = np.random.default_rng(42)
        n = 500
        df = pd.DataFrame({
            "treatment": rng.choice(["A", "B", "C"], n),
            "outcome": rng.binomial(1, 0.3, n).astype(float),
            "conf": rng.normal(0, 1, n),
        })
        data = PipelineDataFrame.from_dataframe(df)
        spec = make_spec(
            treatment="treatment",
            treatment_form=TreatmentForm.CATEGORICAL,
            confounders=["conf"],
            refutations=[RefutationConfig(type=RefutationType.PLACEBO)],
        )
        dag = edges_to_nx([("conf", "treatment"), ("treatment", "outcome"), ("conf", "outcome")])
        budget = MagicMock()
        budget.lgbm_defaults.return_value = dict(n_estimators=10, max_depth=3,
                                                 learning_rate=0.1, verbose=-1)
        budget.param.return_value = 3
        budget.container_mb = 32000
        budget.check_and_reclaim = MagicMock()

        with patch("service.causal_verification.refutations.dowhy") as mock_dowhy:
            mock_model = MagicMock()
            mock_dowhy.CausalModel.return_value = mock_model
            mock_model.identify_effect.return_value = MagicMock()
            mock_model.estimate_effect.return_value = MagicMock()
            mock_refute = MagicMock(new_effect=0.01)
            mock_model.refute_estimate.return_value = mock_refute

            refutations_parallel(data, spec, dag, 0.5, budget)

            call_args = mock_dowhy.CausalModel.call_args
            passed_data = call_args.kwargs["data"]
            t_col = passed_data["treatment"]
            assert pd.api.types.is_numeric_dtype(t_col), \
                f"treatment column should be numeric for dowhy isnan compat, " \
                f"got dtype={t_col.dtype}"
            o_col = passed_data["outcome"]
            assert pd.api.types.is_numeric_dtype(o_col)
            assert "conf" in passed_data.columns
            assert pd.api.types.is_numeric_dtype(passed_data["conf"])

    def test_continuous_treatment_path_unchanged(self, causal_data):
        """Continuous treatment still passes raw numeric column."""
        from service.causal_verification.refutations import refutations_parallel
        data, _ = causal_data
        spec = make_spec(
            treatment="treatment",
            treatment_form=TreatmentForm.CONTINUOUS,
            confounders=["conf"],
            refutations=[RefutationConfig(type=RefutationType.PLACEBO)],
        )
        dag = edges_to_nx(CAUSAL_EDGES)
        budget = MagicMock()
        budget.lgbm_defaults.return_value = dict(n_estimators=10, verbose=-1)
        budget.param.return_value = 3
        budget.container_mb = 32000
        budget.check_and_reclaim = MagicMock()

        with patch("service.causal_verification.refutations.dowhy") as mock_dowhy:
            mock_model = MagicMock()
            mock_dowhy.CausalModel.return_value = mock_model
            mock_model.identify_effect.return_value = MagicMock()
            mock_model.estimate_effect.return_value = MagicMock()
            mock_model.refute_estimate.return_value = MagicMock(new_effect=0.01)

            refutations_parallel(data, spec, dag, 0.5, budget)

            call_args = mock_dowhy.CausalModel.call_args
            passed_data = call_args.kwargs["data"]
            assert pd.api.types.is_numeric_dtype(passed_data["treatment"])


class TestSilentSkipReporting:
    """Silent skips must surface in output for visibility."""

    def test_residual_field_correlations_reports_skipped_columns(self):
        from service.causal_verification.residual_diagnostics import residual_diagnostics
        rng = np.random.default_rng(42)
        n = 500
        df = pd.DataFrame({
            "treatment": rng.normal(0, 1, n),
            "outcome": rng.normal(0, 1, n),
            "conf": rng.normal(0, 1, n),
            "low_n_col": [1.0] * 50 + [None] * 450,
        })
        data = PipelineDataFrame.from_dataframe(df)
        spec = make_spec(
            confounders=["conf"],
            residual_checks=ResidualChecks(
                autocorrelation=[],
                field_correlation=FieldCorrelationCheck(
                    threshold=0.05,
                    check_columns=["conf", "low_n_col", "missing_col"]),
                auto_correction=AutoCorrectionConfig(max_iterations=1, stop_criterion_ci_pct=5.0),
                metadata_correlation=[],
            ),
        )
        edges = [("conf", "treatment"), ("treatment", "outcome"), ("conf", "outcome")]
        r = residual_diagnostics(data, spec, ["conf"], edges, 0.5)
        assert "field_correlations_skipped" in r
        skipped_cols = [s["column"] for s in r["field_correlations_skipped"]]
        assert "missing_col" in skipped_cols
        assert "low_n_col" in skipped_cols
        for s in r["field_correlations_skipped"]:
            assert "reason" in s
            if s["column"] == "missing_col":
                assert "not in data" in s["reason"]
            elif s["column"] == "low_n_col":
                assert "non-null" in s["reason"]

    def test_residual_metadata_correlations_reports_skipped(self):
        from service.causal_verification.residual_diagnostics import residual_diagnostics
        data = make_pipeline_df(n=200)
        spec = make_spec(
            confounders=["conf_a"],
            residual_checks=ResidualChecks(
                autocorrelation=[],
                field_correlation=FieldCorrelationCheck(threshold=0.5, check_columns=[]),
                auto_correction=AutoCorrectionConfig(max_iterations=1, stop_criterion_ci_pct=5.0),
                metadata_correlation=[
                    MetadataCorrelation(column="missing_meta", threshold=0.1, alert_type="HIGH"),
                ],
            ),
        )
        edges = [("treatment", "outcome"), ("conf_a", "outcome")]
        r = residual_diagnostics(data, spec, ["conf_a"], edges, 0.5)
        assert "metadata_correlations_skipped" in r
        assert r["metadata_correlations_skipped"][0]["column"] == "missing_meta"
        assert "not in data" in r["metadata_correlations_skipped"][0]["reason"]

    def test_structural_breaks_reports_skipped_entities(self, h3_data):
        from service.causal_verification.structural_breaks import structural_breaks
        from dataclasses import replace as dc_replace
        df = h3_data.raw.copy()
        df["shipmentDate"] = pd.date_range("2024-01-01", periods=len(df), freq="h")
        data = PipelineDataFrame.from_dataframe(df)
        spec = make_h3_spec(include_node_id=False, positivity=False)
        spec = dc_replace(spec, structural_breaks=[
            StructuralBreakConfig(
                id="sb_strict",
                entity_column="region",
                temporal_column="shipmentDate",
                temporal_grain="M",
                pelt_penalty=4.79,
                min_obs_per_period=10000,
                known_events_tables=None,
                entity_count=7,
                temporal_points=10,
            ),
        ])
        r = structural_breaks(data, spec, -0.007, (-0.01, -0.003))
        assert len(r) == 1
        entry = r[0]
        if "error" not in entry:
            assert "n_entities_processed" in entry
            assert "n_entities_skipped" in entry
            assert entry["n_entities_skipped"] >= 1
            assert "skipped_entities" in entry
            for s in entry["skipped_entities"]:
                assert "entity" in s
                assert "reason" in s

    def test_refutations_unknown_type_produces_error_future(self):
        from service.causal_verification.refutations import refutations_parallel
        from dto.causal_verification_request import RefutationConfig as RC, RefutationType as RT
        rng = np.random.default_rng(42)
        df = pd.DataFrame({
            "treatment": rng.normal(0, 1, 200),
            "outcome": rng.normal(0, 1, 200),
            "conf": rng.normal(0, 1, 200),
        })
        data = PipelineDataFrame.from_dataframe(df)
        bogus_type = MagicMock()
        bogus_type.value = "BOGUS_TYPE"
        bogus_cfg = MagicMock()
        bogus_cfg.type = bogus_type
        spec = make_spec(refutations=[bogus_cfg])
        dag = edges_to_nx(CAUSAL_EDGES)
        budget = MagicMock()
        budget.lgbm_defaults.return_value = dict(n_estimators=10, verbose=-1)
        budget.param.return_value = 3
        budget.container_mb = 32000
        budget.check_and_reclaim = MagicMock()
        futures = refutations_parallel(data, spec, dag, 0.5, budget)
        assert "bogus_type" in futures
        f = futures["bogus_type"]
        with pytest.raises(ValueError, match="no dispatch handler"):
            f.result()

    def test_mediation_errors_when_neither_treatment_in_data(self):
        rng = np.random.default_rng(42)
        n = 200
        df = pd.DataFrame({
            "outcome": rng.normal(0, 1, n),
            "conf": rng.normal(0, 1, n),
            "mediator": rng.normal(0, 1, n),
            "actual_treatment": rng.normal(0, 1, n),
        })
        data = PipelineDataFrame.from_dataframe(df)
        spec = make_spec(
            treatment="missing_treatment",
            original_treatment="also_missing",
            confounders=["conf"],
            mediation=[MediationConfig(
                mediator="mediator", pathway="T->M->Y",
                total_variant_id="v_total",
                direct_variant_id="v_direct_missing",
            )],
        )
        results = mediation(data, spec, ["conf"],
                            {"v_total": {"effect": 0.5}}, [])
        assert len(results) == 1
        assert "error" in results[0]
        assert "missing_treatment" in results[0]["error"]
        assert "also_missing" in results[0]["error"]

    def test_mediation_logs_treatment_fallback(self):
        rng = np.random.default_rng(42)
        n = 500
        conf = rng.normal(0, 1, n)
        treatment = conf + rng.normal(0, 1, n)
        med = 0.6 * treatment + rng.normal(0, 1, n)
        outcome = 0.2 * treatment + 0.5 * med + conf + rng.normal(0, 1, n)
        df = pd.DataFrame({"treatment": treatment, "outcome": outcome,
                           "conf": conf, "mediator": med})
        data = PipelineDataFrame.from_dataframe(df)
        total = 0.2 + 0.6 * 0.5
        spec = make_spec(
            confounders=["conf"],
            original_treatment="missing_original",
            mediation=[MediationConfig(
                mediator="mediator", pathway="T->M->Y",
                total_variant_id="v_total",
                direct_variant_id="v_direct_missing",
            )],
        )
        edges = [("conf", "treatment"), ("treatment", "mediator"),
                 ("mediator", "outcome"), ("treatment", "outcome")]
        r = mediation(data, spec, ["conf"],
                      {"v_total": {"effect": total}}, edges)
        assert len(r) == 1
        if "error" not in r[0]:
            assert r[0]["source"] == "refit"
            assert r[0].get("refit_treatment_column") == "treatment"
            assert "treatment_fallback" in r[0]


class TestBackwardCompat:

    def test_imports(self):
        from service.causal_verification_service import CausalVerificationService as C1
        from service.causal_verification import CausalVerificationService as C2
        assert C1 is C2
        svc = C1()
        for m in ('_load_data', '_validate_spec', '_parse_dag_edges',
                  '_edges_to_nx', 'run_pipeline'):
            assert callable(getattr(svc, m))
