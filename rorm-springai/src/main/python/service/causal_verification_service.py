"""
Causal Verification Pipeline service.

Translates the notebook-based causal verification pipeline into a
spec-driven service. Each step is parameterized by the PipelineSpec
(CausalVerificationRequest) rather than hardcoded configuration.
"""

import dowhy
import logging
import math
import networkx as nx
import numpy as np
import pandas as pd
import ruptures
import warnings
from dto.causal_verification_request import (
    CausalVerificationRequest,
    EstimationVariant,
    FilterOperator,
    GrfConfig,
    OverlapStrategy,
    RefutationType,
    SlicingMethod,
    StructuralBreakConfig,
    TreatmentForm,
    UnmeasuredMethod,
)
from econml.dml import CausalForestDML, LinearDML
from econml.inference import BootstrapInference
from itertools import combinations
from lightgbm import LGBMRegressor
from scipy.stats import pearsonr
from sklearn.linear_model import LinearRegression, LogisticRegression
from sklearn.model_selection import KFold, cross_val_score
from statsmodels.stats.diagnostic import acorr_ljungbox
from statsmodels.stats.outliers_influence import variance_inflation_factor
from statsmodels.stats.stattools import durbin_watson
from typing import Any, Callable, Optional

warnings.filterwarnings("ignore")
logger = logging.getLogger(__name__)

LGBM_DEFAULTS = dict(n_estimators=300, max_depth=6, learning_rate=0.05, verbose=-1)
BOOTSTRAP_SAMPLES = 20
GRF_ESTIMATORS = 200
GRF_MIN_LEAF = 50
RANDOM_STATE = 42
CI_ALPHA = 0.05


class CausalVerificationService:

    def __init__(self, db_storage=None, worker_pool=None):
        self._db_storage = db_storage
        self._worker_pool = worker_pool

    def run_pipeline(
            self,
            spec: CausalVerificationRequest,
            datasource,
            progress_callback: Optional[Callable] = None,
            checkpoint: Optional["PipelineCheckpoint"] = None,
    ) -> dict[str, Any]:
        """
        Execute the full 13-step causal verification pipeline.

        When *checkpoint* is provided every step's output variables are
        persisted after completion.  On a retry / resume with the same
        ``run_id`` the pipeline skips already-checkpointed steps and
        reloads their variables, except for step 0 (data loading) which
        always re-runs because DataFrames are too large to serialise.
        """
        from datetime import datetime
        from service.pipeline_checkpoint import PipelineCheckpoint  # noqa: F811

        # Register run as in-progress
        if checkpoint:
            checkpoint.save_run_meta({
                "spec": spec.to_dict(),
                "status": "running",
                "started_at": datetime.utcnow().isoformat(),
                "_score": datetime.utcnow().timestamp(),
            })

        result: dict[str, Any] = {"hypothesis_id": spec.hypothesis_id, "steps": {}}
        aborted = False

        def report(pct: float, msg: str = ""):
            if progress_callback:
                progress_callback(pct, msg)

        def _cp_load(step: str) -> Optional[dict]:
            return checkpoint.load(step) if checkpoint else None

        def _cp_save(step: str, data: dict) -> None:
            if checkpoint:
                checkpoint.save(step, data)

        # ==================================================================
        # Step 0  —  Load data  (always re-runs; DataFrames not persisted)
        # ==================================================================
        report(0.02, "Loading data")
        data = self._load_data(spec, datasource)
        result["row_count"] = len(data)
        if spec.expected_row_count and len(data) != spec.expected_row_count:
            result["row_count_mismatch"] = {
                "expected": spec.expected_row_count,
                "actual": len(data),
            }

        # ==================================================================
        # Step 1  —  D-sep refinement
        # ==================================================================
        cp = _cp_load("dsep")
        if cp is not None:
            report(0.05, "D-sep refinement (cached)")
            dsep_result = cp["dsep_result"]
            refined_edges = [tuple(e) for e in cp["refined_edges"]]
        else:
            report(0.05, "D-separation refinement")
            edges = self._parse_dag_edges(spec.dag_edges)
            dsep_result = self._dsep_refinement(data, edges, spec.dsep_threshold)
            refined_edges = dsep_result["refined_edges"]
            _cp_save("dsep", {
                "dsep_result": dsep_result,
                "refined_edges": refined_edges,
            })
        result["steps"]["dsep"] = dsep_result
        dag_dot = self._edges_to_dot(refined_edges)

        # ==================================================================
        # Step 2  —  Identification
        # ==================================================================
        cp = _cp_load("identification")
        if cp is not None:
            report(0.10, "Identification (cached)")
            confounders = cp["confounders"]
            identification_result = cp["identification_result"]
        else:
            report(0.10, "Verifying identification")
            confounders = list(spec.adjustment_set)
            identification_result = {
                "adjustment_set": confounders,
                "mediators_excluded": [m.column for m in spec.mediators_excluded],
            }
            _cp_save("identification", {
                "confounders": confounders,
                "identification_result": identification_result,
            })
        result["steps"]["identification"] = identification_result

        # ==================================================================
        # Step 3  —  Estimation variants
        # ==================================================================
        cp = _cp_load("estimation")
        if cp is not None:
            report(0.15, "Estimation variants (cached)")
            estimation_results = cp["estimation_results"]
            primary_effect = cp["primary_effect"]
            primary_ci = cp.get("primary_ci")
        else:
            report(0.15, "Running estimation variants")
            estimation_results = {}
            primary_variant = spec.estimation_variants[0]
            for i, variant in enumerate(spec.estimation_variants):
                report(0.15 + 0.15 * (i / len(spec.estimation_variants)),
                       f"Estimation variant {variant.id}")
                est = self._run_estimation_variant(
                    data, variant, refined_edges, dag_dot, spec.outcome
                )
                estimation_results[variant.id] = est

            primary_effect = estimation_results[primary_variant.id]["effect"]
            primary_ci = estimation_results[primary_variant.id].get("ci")
            _cp_save("estimation", {
                "estimation_results": estimation_results,
                "primary_effect": primary_effect,
                "primary_ci": primary_ci,
            })
        result["steps"]["estimation"] = estimation_results

        # ==================================================================
        # Step 4  —  Quality gates
        # ==================================================================
        cp = _cp_load("gates")
        if cp is not None:
            report(0.35, "Quality gates (cached)")
            gates_result = cp["gates_result"]
            aborted = cp["aborted"]
        else:
            report(0.35, "Quality gates")
            gates_result = self._quality_gates(
                data, spec, confounders, primary_effect, primary_ci
            )
            aborted = bool(gates_result.get("abort"))
            _cp_save("gates", {
                "gates_result": gates_result,
                "aborted": aborted,
            })
        result["steps"]["gates"] = gates_result
        if aborted:
            result["aborted"] = True
            result["abort_reason"] = gates_result.get("abort_reason")

        # ==================================================================
        # Step 5  —  Mediation
        # ==================================================================
        if spec.mediation and not aborted:
            cp = _cp_load("mediation")
            if cp is not None:
                report(0.40, "Mediation (cached)")
                result["steps"]["mediation"] = cp["mediation_result"]
            else:
                report(0.40, "Mediation decomposition")
                mediation_result = self._mediation(
                    data, spec, confounders, estimation_results, refined_edges
                )
                _cp_save("mediation", {"mediation_result": mediation_result})
                result["steps"]["mediation"] = mediation_result
        else:
            result["steps"]["mediation"] = None

        # ==================================================================
        # Step 6  —  GRF heterogeneity
        # ==================================================================
        if spec.grf_configs and not aborted:
            cp = _cp_load("grf")
            if cp is not None:
                report(0.45, "GRF (cached)")
                result["steps"]["grf"] = cp["grf_result"]
            else:
                report(0.45, "GRF heterogeneity analysis")
                grf_result = self._grf_heterogeneity(data, spec, confounders)
                _cp_save("grf", {"grf_result": grf_result})
                result["steps"]["grf"] = grf_result
        else:
            result["steps"]["grf"] = None

        # ==================================================================
        # Step 7  —  Refutations
        # ==================================================================
        if spec.refutations and not aborted:
            cp = _cp_load("refutations")
            if cp is not None:
                report(0.55, "Refutations (cached)")
                result["steps"]["refutations"] = cp["refutations_result"]
            else:
                report(0.55, "Refutations")
                refutations_result = self._refutations(
                    data, spec, dag_dot, primary_effect
                )
                _cp_save("refutations", {"refutations_result": refutations_result})
                result["steps"]["refutations"] = refutations_result

        # ==================================================================
        # Step 8  —  Unmeasured confounding
        # ==================================================================
        if spec.unmeasured_confounding and not aborted:
            cp = _cp_load("unmeasured_confounding")
            if cp is not None:
                report(0.62, "Unmeasured confounding (cached)")
                result["steps"]["unmeasured_confounding"] = cp["uc_result"]
            else:
                report(0.62, "Unmeasured confounding")
                uc_result = self._unmeasured_confounding(
                    data, spec, estimation_results
                )
                _cp_save("unmeasured_confounding", {"uc_result": uc_result})
                result["steps"]["unmeasured_confounding"] = uc_result

        # ==================================================================
        # Step 9  —  Sensitivity
        # ==================================================================
        if not aborted:
            cp = _cp_load("sensitivity")
            if cp is not None:
                report(0.68, "Sensitivity (cached)")
                result["steps"]["sensitivity"] = cp["sensitivity_result"]
            else:
                report(0.68, "Specification sensitivity")
                sensitivity_result = self._sensitivity(
                    data, spec, refined_edges, primary_effect, estimation_results
                )
                _cp_save("sensitivity", {"sensitivity_result": sensitivity_result})
                result["steps"]["sensitivity"] = sensitivity_result

        # ==================================================================
        # Step 10  —  Structural breaks
        # ==================================================================
        if spec.structural_breaks and not aborted:
            cp = _cp_load("structural_breaks")
            if cp is not None:
                report(0.75, "Structural breaks (cached)")
                result["steps"]["structural_breaks"] = cp["breaks_result"]
            else:
                report(0.75, "Structural break detection")
                breaks_result = self._structural_breaks(
                    data, spec, primary_effect, primary_ci
                )
                _cp_save("structural_breaks", {"breaks_result": breaks_result})
                result["steps"]["structural_breaks"] = breaks_result

        # ==================================================================
        # Step 11  —  Residual diagnostics
        # ==================================================================
        if not aborted:
            cp = _cp_load("residual_diagnostics")
            if cp is not None:
                report(0.82, "Residual diagnostics (cached)")
                residual_result = cp["residual_result"]
                if cp.get("corrected_effect") is not None:
                    primary_effect = cp["corrected_effect"]
            else:
                report(0.82, "Residual diagnostics")
                residual_result = self._residual_diagnostics(
                    data, spec, confounders, refined_edges, primary_effect
                )
                corrected = residual_result.get("corrected_effect")
                if corrected is not None:
                    primary_effect = corrected
                _cp_save("residual_diagnostics", {
                    "residual_result": residual_result,
                    "corrected_effect": corrected,
                })
            result["steps"]["residual_diagnostics"] = residual_result

        # ==================================================================
        # Step 12  —  Range checks
        # ==================================================================
        if not aborted:
            cp = _cp_load("range_checks")
            if cp is not None:
                report(0.90, "Range checks (cached)")
                result["steps"]["range_checks"] = cp["range_result"]
            else:
                report(0.90, "Range restriction checks")
                range_result = self._range_checks(
                    data, spec, confounders, estimation_results
                )
                _cp_save("range_checks", {"range_result": range_result})
                result["steps"]["range_checks"] = range_result

        # ==================================================================
        # Step 13  —  Externalization
        # ==================================================================
        if spec.externalization and not aborted:
            cp = _cp_load("externalization")
            if cp is not None:
                report(0.95, "Externalization (cached)")
                result["steps"]["externalization"] = cp["ext_result"]
            else:
                report(0.95, "Externalization tests")
                ext_result = self._externalization(data, spec, result)
                _cp_save("externalization", {"ext_result": ext_result})
                result["steps"]["externalization"] = ext_result

        # ==================================================================
        # Final assembly
        # ==================================================================
        result["final_effect"] = primary_effect
        result["final_ci"] = primary_ci
        result["discrepancy_log"] = [
            {"field": d.field_name, "generator_value": d.generator_value,
             "compiler_value": d.compiler_value, "resolution": d.resolution}
            for d in spec.discrepancy_log
        ]

        # Freeze run
        if checkpoint:
            checkpoint.save_run_meta({
                "spec": spec.to_dict(),
                "status": "completed",
                "completed_at": datetime.utcnow().isoformat(),
                "_score": datetime.utcnow().timestamp(),
                "result": result,
            })

        report(1.0, "Pipeline complete")
        return result

    # ------------------------------------------------------------------
    # Step 0: Data loading
    # ------------------------------------------------------------------

    def _load_data(self, spec: CausalVerificationRequest, datasource) -> pd.DataFrame:
        sql = spec.datasource.sql
        bind_vars = spec.datasource.bind_variables
        result = datasource.fetch(sql, bind_vars)
        data = result.dataframe.to_pandas()
        if spec.strip_columns:
            data = data.drop(
                columns=[c for c in spec.strip_columns if c in data.columns],
                errors="ignore",
            )
        data = self._encode_categoricals(data)
        return data

    @staticmethod
    def _encode_categoricals(df: pd.DataFrame) -> pd.DataFrame:
        """Label-encode object/category columns to integers for DML compatibility.

        Label encoding (not one-hot) preserves column names so the DAG graph
        stays valid.  LGBMRegressor nuisance models handle ordinal-encoded
        categoricals natively; the DML final-stage LinearRegression only sees
        residualized Y/T, not W directly, so arbitrary ordering is harmless.
        """
        cat_cols = df.select_dtypes(include=["object", "category"]).columns
        if cat_cols.empty:
            return df
        df = df.copy()
        for col in cat_cols:
            df[col] = df[col].astype("category").cat.codes
        return df

    # ------------------------------------------------------------------
    # Step 1: D-sep refinement
    # ------------------------------------------------------------------

    def _dsep_refinement(
            self, data: pd.DataFrame, edges: list[tuple[str, str]], threshold: float
    ) -> dict:
        all_nodes = list({n for e in edges for n in e})
        violations, confirmed = self._run_dsep_tests(data, edges, all_nodes, threshold)
        refined_edges, fallback = self._add_fallback_edges(edges, violations)
        return {
            "broad_edge_count": len(edges),
            "refined_edge_count": len(refined_edges),
            "violation_count": len(violations),
            "confirmed_count": len(confirmed),
            "fallback_edges": [list(e) for e in fallback],
            "refined_edges": refined_edges,
            "violations": [
                {"node_a": v["node_a"], "node_b": v["node_b"],
                 "correlation": v["correlation"], "p_value": v["p_value"]}
                for v in violations
            ],
        }

    def _run_dsep_tests(self, data, edges, all_nodes, threshold):
        implications = self._get_dsep_implications(edges, all_nodes)
        buffer = {}
        for a, b, cond in implications:
            r = self._test_ci(data, a, b, cond)
            buffer[(a, b, frozenset(cond))] = r
        max_r = max((abs(r["correlation"]) for r in buffer.values()), default=0)
        violations, confirmed = [], []
        for (a, b, _), r in buffer.items():
            if r["p_value"] < CI_ALPHA and abs(r["correlation"]) > max_r ** 1.5:
                violations.append(r)
            else:
                confirmed.append(r)
        return violations, confirmed

    @staticmethod
    def _get_dsep_implications(edges, all_nodes):
        G = nx.DiGraph(edges)
        seen, unique = set(), []
        for a, b in combinations(all_nodes, 2):
            if G.has_edge(a, b) or G.has_edge(b, a):
                continue
            parents = set(G.predecessors(a)) | set(G.predecessors(b)) - {a, b}
            for cond in [parents, set()]:
                if nx.is_d_separator(G, {a}, {b}, cond):
                    key = (min(a, b), max(a, b), tuple(sorted(cond)))
                    if key not in seen:
                        seen.add(key)
                        unique.append((a, b, cond))
        return unique

    @staticmethod
    def _test_ci(data, a, b, cond_set):
        a_vals = data[a].values.astype(float)
        b_vals = data[b].values.astype(float)
        if not cond_set:
            r, p = pearsonr(a_vals, b_vals)
        else:
            X = data[list(cond_set)].values.astype(float)
            res_a = a_vals - LinearRegression().fit(X, a_vals).predict(X)
            res_b = b_vals - LinearRegression().fit(X, b_vals).predict(X)
            r, p = pearsonr(res_a, res_b)
        return {"node_a": a, "node_b": b, "cond_set": list(cond_set),
                "correlation": float(r), "p_value": float(p)}

    @staticmethod
    def _add_fallback_edges(current_edges, violations):
        added = []
        edges = list(current_edges)
        for v in violations:
            a, b = v["node_a"], v["node_b"]
            if (a, b) in edges or (b, a) in edges:
                continue
            G_temp = nx.DiGraph(edges)
            a_d = len(nx.ancestors(G_temp, a)) if a in G_temp else 0
            b_d = len(nx.ancestors(G_temp, b)) if b in G_temp else 0
            edge = (a, b) if a_d <= b_d else (b, a)
            G_test = nx.DiGraph(edges + [edge])
            if nx.is_directed_acyclic_graph(G_test):
                edges.append(edge)
                added.append(edge)
        return edges, added

    # ------------------------------------------------------------------
    # Step 3: Estimation
    # ------------------------------------------------------------------

    def _run_estimation_variant(
            self,
            data: pd.DataFrame,
            variant: EstimationVariant,
            refined_edges: list[tuple[str, str]],
            dag_dot: str,
            outcome_col: str,
    ) -> dict[str, Any]:
        df = self._apply_variant_filter(data, variant)
        treatment_col = variant.treatment_column
        W_cols = variant.w_columns
        discrete = variant.treatment_form != TreatmentForm.CONTINUOUS

        if variant.treatment_form == TreatmentForm.BINARY_THRESHOLD and variant.threshold_value is not None:
            bin_col = f"_bin_{treatment_col}_{variant.threshold_value}"
            df[bin_col] = (df[treatment_col] > variant.threshold_value).astype(int)
            treatment_col = bin_col
            refined_edges = [
                (bin_col if s == variant.treatment_column else s,
                 bin_col if d == variant.treatment_column else d)
                for s, d in refined_edges
            ]

        return self._estimate_dml(
            df, variant, W_cols, treatment_col, discrete, refined_edges, outcome_col
        )

    def _estimate_dml(
            self,
            df: pd.DataFrame,
            variant: EstimationVariant,
            w_cols: list[str],
            treatment_col: str,
            discrete: bool,
            refined_edges: list[tuple[str, str]],
            outcome_col: str,
    ) -> dict[str, Any]:
        """Run DML estimation via DoWhy for a single variant."""
        dag_dot = self._edges_to_dot(refined_edges)

        dml_params = {
            "init_params": {
                "model_y": LGBMRegressor(**LGBM_DEFAULTS),
                "model_t": LGBMRegressor(**LGBM_DEFAULTS),
                "model_final": LinearRegression(),
                "discrete_treatment": discrete,
            },
            "fit_params": {
                "inference": BootstrapInference(
                    n_bootstrap_samples=BOOTSTRAP_SAMPLES, n_jobs=-1
                ),
            },
        }

        try:
            model = dowhy.CausalModel(
                data=df, treatment=treatment_col,
                outcome=outcome_col, graph=dag_dot,
            )
            identified = model.identify_effect(proceed_when_unidentifiable=False)
            estimate = model.estimate_effect(
                identified, method_name="backdoor.econml.dml.DML",
                method_params=dml_params,
            )
            effect = float(estimate.value)
            econml_obj = estimate.params["_estimator_object"]
            ci_raw = econml_obj.effect_interval(alpha=CI_ALPHA)
            ci = (float(ci_raw[0].flatten()[0]), float(ci_raw[1].flatten()[0]))
        except Exception as e:
            logger.warning(f"Variant {variant.id} estimation failed: {e}")
            return {"variant_id": variant.id, "effect": None, "ci": None, "error": str(e)}

        return {
            "variant_id": variant.id,
            "effect": effect,
            "ci": ci,
            "treatment_column": treatment_col,
            "w_columns": w_cols,
            "n_obs": len(df),
            "discrete": discrete,
        }

    # ------------------------------------------------------------------
    # Step 4: Quality gates
    # ------------------------------------------------------------------

    def _quality_gates(
            self, data, spec, confounders, effect, ci
    ) -> dict[str, Any]:
        gates = spec.gates
        result: dict[str, Any] = {"abort": False}

        # Nuisance R2
        outcome_r2 = float(np.mean(cross_val_score(
            LGBMRegressor(**LGBM_DEFAULTS), data[confounders], data[spec.outcome],
            cv=5, scoring="r2")))
        treatment_r2 = float(np.mean(cross_val_score(
            LGBMRegressor(**LGBM_DEFAULTS), data[confounders], data[spec.treatment],
            cv=5, scoring="r2")))

        result["nuisance_r2"] = {
            "outcome_r2": outcome_r2,
            "treatment_r2": treatment_r2,
            "outcome_status": "abort" if outcome_r2 < gates.nuisance_r2.outcome_abort
            else "flag" if outcome_r2 < gates.nuisance_r2.outcome_flag
            else "pass",
            "treatment_status": "flag" if treatment_r2 < gates.nuisance_r2.treatment_flag
            else "structural" if treatment_r2 > gates.nuisance_r2.treatment_structural_max_r2
            else "pass",
        }

        if outcome_r2 < gates.nuisance_r2.outcome_abort:
            result["abort"] = True
            result[
                "abort_reason"] = f"Outcome R2 {outcome_r2:.3f} below abort threshold {gates.nuisance_r2.outcome_abort}"
            return result

        # Sanity
        if effect is not None:
            direction_ok = np.sign(effect) == gates.sanity.expected_direction
            magnitude = abs(effect)
            result["sanity"] = {
                "direction_ok": direction_ok,
                "effect_magnitude": magnitude,
                "abort_magnitude": gates.sanity.abort_magnitude,
                "flag_magnitude": gates.sanity.flag_magnitude,
                "status": "abort" if magnitude > gates.sanity.abort_magnitude
                else "flag" if magnitude > gates.sanity.flag_magnitude
                else "pass",
            }
            if magnitude > gates.sanity.abort_magnitude:
                result["abort"] = True
                result["abort_reason"] = f"Effect magnitude {magnitude:.4f} exceeds abort threshold"
                return result
            if not direction_ok:
                result["sanity"]["warning"] = "Effect direction does not match expected"

        return result

    # ------------------------------------------------------------------
    # Step 5: Mediation
    # ------------------------------------------------------------------

    def _mediation(
            self, data, spec, confounders, estimation_results, refined_edges
    ) -> list[dict]:
        results = []
        for med in spec.mediation:
            total_est = estimation_results.get(med.total_variant_id, {})
            total_effect = total_est.get("effect")
            if total_effect is None:
                results.append({"mediator": med.mediator, "error": "total variant not found"})
                continue

            W_med = data[confounders + [med.mediator]].values
            try:
                dml = LinearDML(
                    model_y=LGBMRegressor(**LGBM_DEFAULTS),
                    model_t=LGBMRegressor(**LGBM_DEFAULTS),
                    discrete_treatment=False,
                )
                dml.fit(data[spec.outcome].values, data[spec.treatment].values, W=W_med)
                direct = float(dml.effect().mean())
                mediated = total_effect - direct
                fraction = mediated / total_effect if total_effect != 0 else 0
                results.append({
                    "mediator": med.mediator,
                    "pathway": med.pathway,
                    "direct_effect": direct,
                    "mediated_effect": mediated,
                    "fraction": fraction,
                })
            except Exception as e:
                results.append({"mediator": med.mediator, "error": str(e)})
        return results

    # ------------------------------------------------------------------
    # Step 6: GRF heterogeneity
    # ------------------------------------------------------------------

    def _grf_heterogeneity(self, data, spec, confounders) -> list[dict]:
        results = []
        for cfg in spec.grf_configs:
            try:
                X_grf = data[cfg.modifier_columns].values
                Y = data[spec.outcome].values
                T = data[spec.treatment].values
                W = data[confounders].values

                grf = CausalForestDML(
                    model_y=LGBMRegressor(**LGBM_DEFAULTS),
                    model_t=LGBMRegressor(**LGBM_DEFAULTS),
                    n_estimators=GRF_ESTIMATORS,
                    min_samples_leaf=GRF_MIN_LEAF,
                    random_state=RANDOM_STATE,
                )
                grf.fit(Y=Y, T=T, X=X_grf, W=W)
                cates = grf.effect(X=X_grf)

                slices = {}
                for col, method in cfg.slicing.items():
                    if method == "unique":
                        for val in sorted(data[col].unique()):
                            subset = cates[data[col] == val]
                            slices[f"{col}={val}"] = {
                                "mean_cate": float(subset.mean()),
                                "std_cate": float(subset.std()),
                                "n": int(len(subset)),
                            }
                    elif method == "quartile":
                        q_col = pd.qcut(data[col], 4, duplicates="drop")
                        for q, idx in data.groupby(q_col).groups.items():
                            slices[f"{col}={q}"] = {
                                "mean_cate": float(cates[idx].mean()),
                                "std_cate": float(cates[idx].std()),
                                "n": int(len(idx)),
                            }

                importances = {
                    name: float(imp)
                    for name, imp in zip(cfg.modifier_columns, grf.feature_importances_)
                }
                results.append({
                    "config_id": cfg.id,
                    "slices": slices,
                    "feature_importances": importances,
                    "mean_cate": float(cates.mean()),
                    "std_cate": float(cates.std()),
                })
            except Exception as e:
                results.append({"config_id": cfg.id, "error": str(e)})
        return results

    # ------------------------------------------------------------------
    # Step 7: Refutations
    # ------------------------------------------------------------------

    def _refutations(self, data, spec, dag_dot, primary_effect) -> dict[str, Any]:
        refute_params = {
            "init_params": {
                "model_y": LGBMRegressor(**LGBM_DEFAULTS),
                "model_t": LGBMRegressor(**LGBM_DEFAULTS),
                "model_final": LinearRegression(),
                "discrete_treatment": False,
            },
            "fit_params": {},
        }

        model = dowhy.CausalModel(
            data=data, treatment=spec.treatment,
            outcome=spec.outcome, graph=dag_dot,
        )
        identified = model.identify_effect(proceed_when_unidentifiable=False)
        est = model.estimate_effect(
            identified, method_name="backdoor.econml.dml.DML",
            method_params=refute_params,
        )

        results = {}
        for ref_cfg in spec.refutations:
            try:
                if ref_cfg.type == RefutationType.PLACEBO:
                    r = model.refute_estimate(
                        identified, est,
                        method_name="placebo_treatment_refuter",
                        placebo_type="permute",
                    )
                    ratio = abs(r.new_effect) / abs(primary_effect) if primary_effect else 0
                    results["placebo"] = {
                        "new_effect": float(r.new_effect),
                        "ratio": float(ratio),
                        "flag": ratio > spec.gates.placebo.flag_ratio,
                    }
                elif ref_cfg.type == RefutationType.RANDOM_CAUSE:
                    r = model.refute_estimate(
                        identified, est, method_name="random_common_cause",
                    )
                    shift = (abs(r.new_effect - primary_effect) / abs(primary_effect)
                             if primary_effect else 0)
                    results["random_cause"] = {
                        "new_effect": float(r.new_effect),
                        "shift_pct": float(shift),
                    }
                elif ref_cfg.type == RefutationType.SUBSET:
                    r = model.refute_estimate(
                        identified, est,
                        method_name="data_subset_refuter", subset_fraction=0.8,
                    )
                    shift = (abs(r.new_effect - primary_effect) / abs(primary_effect)
                             if primary_effect else 0)
                    results["subset"] = {
                        "new_effect": float(r.new_effect),
                        "shift_pct": float(shift),
                    }
            except Exception as e:
                results[ref_cfg.type.value.lower()] = {"error": str(e)}

        return results

    # ------------------------------------------------------------------
    # Step 8: Unmeasured confounding
    # ------------------------------------------------------------------

    def _unmeasured_confounding(self, data, spec, estimation_results) -> list[dict]:
        baseline_rate = float(data[spec.outcome].mean())
        results = []
        for uc in spec.unmeasured_confounding:
            variant = estimation_results.get(uc.variant_id, {})
            effect = variant.get("effect")
            ci = variant.get("ci")
            if effect is None:
                results.append({"variant_id": uc.variant_id, "error": "variant not found"})
                continue

            if uc.method == UnmeasuredMethod.E_VALUE:
                iqr = float(data[spec.treatment].quantile(0.75)
                            - data[spec.treatment].quantile(0.25))
                multiplier = iqr
                abs_eff = abs(effect * multiplier)
                rr = (baseline_rate + abs_eff) / baseline_rate if baseline_rate > 0 else 1
                e_point = rr + math.sqrt(rr * (rr - 1)) if rr > 1 else 1.0

                e_ci = 1.0
                if ci:
                    abs_ci = abs(ci[0] * multiplier)
                    rr_ci = max(1.0, (baseline_rate + abs_ci) / baseline_rate) if baseline_rate > 0 else 1
                    e_ci = rr_ci + math.sqrt(rr_ci * (rr_ci - 1)) if rr_ci > 1 else 1.0

                results.append({
                    "variant_id": uc.variant_id,
                    "method": "E_VALUE",
                    "rr": float(rr),
                    "e_value_point": float(e_point),
                    "e_value_ci": float(e_ci),
                    "null_hypothesis": uc.null_hypothesis,
                })
            else:
                results.append({
                    "variant_id": uc.variant_id,
                    "method": "ROSENBAUM_BOUNDS",
                    "notes": uc.notes,
                })
        return results

    # ------------------------------------------------------------------
    # Step 9: Sensitivity
    # ------------------------------------------------------------------

    def _sensitivity(self, data, spec, refined_edges, primary_effect, estimation_results) -> dict:
        result: dict[str, Any] = {}

        # Confounder drops
        drops = []
        for drop in spec.sensitivity.confounder_drops:
            dag_v = self._edges_to_dot(
                [(s, d) for s, d in refined_edges
                 if s != drop.column and d != drop.column]
            )
            est = self._run_dml_quick(data, spec.treatment, spec.outcome, dag_v)
            deviation = (abs(est - primary_effect) / abs(primary_effect) * 100
                         if est is not None and primary_effect else None)
            drops.append({
                "column": drop.column,
                "effect": float(est) if est is not None else None,
                "deviation_pct": float(deviation) if deviation is not None else None,
                "threshold_pct": drop.deviation_threshold_pct,
                "flag": deviation is not None and deviation > drop.deviation_threshold_pct,
            })
        result["confounder_drops"] = drops

        # Confounder adds
        adds = []
        for add in spec.sensitivity.confounder_adds:
            if add.column not in data.columns:
                adds.append({"column": add.column, "error": "column not in data"})
                continue
            dag_v = self._edges_to_dot(refined_edges + [(add.column, spec.outcome)])
            est = self._run_dml_quick(data, spec.treatment, spec.outcome, dag_v)
            deviation = (abs(est - primary_effect) / abs(primary_effect) * 100
                         if est is not None and primary_effect else None)
            adds.append({
                "column": add.column,
                "reasoning": add.reasoning,
                "effect": float(est) if est is not None else None,
                "deviation_pct": float(deviation) if deviation is not None else None,
            })
        result["confounder_adds"] = adds

        # Threshold variants
        thresh = []
        for tv in spec.sensitivity.threshold_variants:
            col = f"_thresh_{tv.threshold}"
            data[col] = (data[spec.treatment] > tv.threshold).astype(int)
            n_treated = int(data[col].sum())
            n_control = int((~data[col].astype(bool)).sum())
            dag_v = self._edges_to_dot(
                [(col if s == spec.treatment else s,
                  col if d == spec.treatment else d)
                 for s, d in refined_edges]
            )
            est = self._run_dml_quick(data, col, spec.outcome, dag_v)
            thresh.append({
                "threshold": tv.threshold,
                "effect": float(est) if est is not None else None,
                "n_treated": n_treated,
                "n_control": n_control,
                "expected_n_treated": tv.expected_n_treated,
                "expected_n_control": tv.expected_n_control,
            })
        result["threshold_variants"] = thresh

        # Model variants
        mvs = []
        for mv in spec.sensitivity.model_variants:
            primary = estimation_results.get(mv.primary_variant_id, {})
            primary_eff = primary.get("effect")
            mvs.append({
                "primary_variant_id": mv.primary_variant_id,
                "alternative_model_type": mv.alternative_model_type,
                "primary_effect": float(primary_eff) if primary_eff is not None else None,
            })
        result["model_variants"] = mvs

        return result

    # ------------------------------------------------------------------
    # Step 10: Structural breaks
    # ------------------------------------------------------------------

    def _structural_breaks(self, data, spec, effect, ci) -> list[dict]:
        results = []
        for sb in spec.structural_breaks:
            if sb.entity_column not in data.columns or sb.temporal_column not in data.columns:
                results.append({"id": sb.id, "error": "column not found"})
                continue

            try:
                data["_period"] = pd.to_datetime(data[sb.temporal_column]).dt.to_period(
                    sb.temporal_grain[0].upper()
                )
                agg = (
                    data.groupby([sb.entity_column, "_period"])
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

    # ------------------------------------------------------------------
    # Step 11: Residual diagnostics
    # ------------------------------------------------------------------

    def _residual_diagnostics(self, data, spec, confounders, refined_edges, effect) -> dict:
        result: dict[str, Any] = {}

        # Compute residuals
        X_c = data[confounders].values
        Y_v = data[spec.outcome].values
        T_v = data[spec.treatment].values
        y_res = np.zeros(len(data))
        t_res = np.zeros(len(data))
        kf = KFold(n_splits=5, shuffle=True, random_state=RANDOM_STATE)
        for tr, te in kf.split(data):
            m_y = LGBMRegressor(**LGBM_DEFAULTS).fit(X_c[tr], Y_v[tr])
            m_t = LGBMRegressor(**LGBM_DEFAULTS).fit(X_c[tr], T_v[tr])
            y_res[te] = Y_v[te] - m_y.predict(X_c[te])
            t_res[te] = T_v[te] - m_t.predict(X_c[te])
        final_res = y_res - effect * t_res

        # Autocorrelation
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

        # Field correlations
        fc = spec.residual_checks.field_correlation
        correlations = []
        for col in fc.check_columns:
            if col not in data.columns:
                continue
            vals = data[col].values
            try:
                vals = vals.astype(float)
            except (ValueError, TypeError):
                continue
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

        # Auto-correction
        corrections = []
        corrected_value = effect
        ac_cfg = spec.residual_checks.auto_correction
        for c in correlations[:ac_cfg.max_iterations]:
            dag_aug = self._edges_to_dot(refined_edges + [(c["column"], spec.outcome)])
            corrected = self._run_dml_quick(data, spec.treatment, spec.outcome, dag_aug)
            if corrected is not None:
                delta_frac = (abs(corrected - corrected_value) / abs(corrected_value)
                              if corrected_value != 0 else 0)
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
        result["corrected_effect"] = float(corrected_value) if corrections else None

        # Metadata correlations
        meta_results = []
        for mc in spec.residual_checks.metadata_correlation:
            if mc.column not in data.columns:
                continue
            vals = data[mc.column].values
            try:
                vals = vals.astype(float)
            except (ValueError, TypeError):
                continue
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

    # ------------------------------------------------------------------
    # Step 12: Range checks
    # ------------------------------------------------------------------

    def _range_checks(self, data, spec, confounders, estimation_results) -> dict:
        result: dict[str, Any] = {}

        # VIF
        vif_cols = confounders + [spec.treatment]
        X_vif = data[vif_cols].dropna()
        vif_values = {}
        for i, col in enumerate(X_vif.columns):
            try:
                vif_values[col] = float(variance_inflation_factor(X_vif.values, i))
            except Exception:
                vif_values[col] = None
        result["vif"] = {
            "values": vif_values,
            "threshold": spec.range_checks.vif.threshold,
            "flagged": [col for col, v in vif_values.items()
                        if v is not None and v > spec.range_checks.vif.threshold],
        }

        # Treatment CV
        cv = float(data[spec.treatment].std() / data[spec.treatment].mean()) \
            if data[spec.treatment].mean() != 0 else 0
        result["treatment_cv"] = cv

        # Overlap
        overlaps = []
        for ov in spec.range_checks.overlap:
            variant = estimation_results.get(ov.variant_id, {})
            t_col = variant.get("treatment_column", spec.treatment)
            if t_col not in data.columns:
                overlaps.append({"variant_id": ov.variant_id, "error": "column not found"})
                continue
            try:
                binary = data[t_col]
                if binary.nunique() > 2:
                    binary = (binary > binary.median()).astype(int)
                ps = LogisticRegression(max_iter=1000).fit(
                    data[confounders], binary
                ).predict_proba(data[confounders])[:, 1]
                c_lo = max(np.percentile(ps[binary == 1], 5),
                           np.percentile(ps[binary == 0], 5))
                c_hi = min(np.percentile(ps[binary == 1], 95),
                           np.percentile(ps[binary == 0], 95))
                overlap_val = float(np.mean((ps >= c_lo) & (ps <= c_hi)))
                overlaps.append({
                    "variant_id": ov.variant_id,
                    "overlap": overlap_val,
                    "threshold": ov.threshold,
                    "flagged": overlap_val < ov.threshold,
                    "response_strategy": ov.response_strategy.value,
                })
            except Exception as e:
                overlaps.append({"variant_id": ov.variant_id, "error": str(e)})
        result["overlap"] = overlaps

        # Variance
        var_checks = []
        for vc in spec.range_checks.variance:
            if vc.column in data.columns:
                std = float(data[vc.column].std())
                mean = float(data[vc.column].mean())
                var_checks.append({
                    "column": vc.column,
                    "std": std,
                    "cv": float(std / mean) if mean != 0 else None,
                    "structural_note": vc.structural_note,
                })
        result["variance"] = var_checks

        return result

    # ------------------------------------------------------------------
    # Step 13: Externalization
    # ------------------------------------------------------------------

    def _externalization(self, data, spec, pipeline_result) -> dict:
        result: dict[str, Any] = {}

        # Domain rankings
        grf_results = pipeline_result.get("steps", {}).get("grf")
        if grf_results:
            discovered_slopes = {}
            for grf_r in grf_results:
                if isinstance(grf_r, dict) and "slices" in grf_r:
                    for key, val in grf_r["slices"].items():
                        discovered_slopes[key] = val["mean_cate"]

            rankings = []
            for dr in spec.externalization.domain_rankings:
                rankings.append({
                    "source": dr.source,
                    "domain_ranking": dr.domain_ranking,
                    "comparison_method": dr.comparison_method,
                    "scope": dr.scope,
                    "expected_concordance": dr.expected_concordance,
                    "discovered_slopes": discovered_slopes,
                })
            result["domain_rankings"] = rankings

        # Allocation bias
        alloc_results = []
        for ab in spec.externalization.allocation_bias:
            if ab.treatment_column in data.columns and ab.grouping_column in data.columns:
                try:
                    ct = pd.crosstab(
                        data[ab.treatment_column], data[ab.grouping_column], normalize="index"
                    )
                    max_dev = float(ct.values.std())
                    alloc_results.append({
                        "treatment_column": ab.treatment_column,
                        "grouping_column": ab.grouping_column,
                        "max_deviation": max_dev,
                        "flagged": max_dev > ab.flag_threshold,
                    })
                except Exception as e:
                    alloc_results.append({
                        "treatment_column": ab.treatment_column,
                        "grouping_column": ab.grouping_column,
                        "error": str(e),
                    })
        result["allocation_bias"] = alloc_results

        return result

    # ------------------------------------------------------------------
    # Utility methods
    # ------------------------------------------------------------------

    @staticmethod
    def _parse_dag_edges(dag_str: str) -> list[tuple[str, str]]:
        edges = []
        for part in dag_str.replace("\n", ";").split(";"):
            part = part.strip()
            if "->" in part:
                src, dst = part.split("->", 1)
                edges.append((src.strip(), dst.strip()))
        return edges

    @staticmethod
    def _edges_to_dot(edges: list[tuple[str, str]]) -> str:
        lines = ["digraph {"]
        for src, dst in edges:
            lines.append(f"    {src} -> {dst};")
        lines.append("}")
        return "\n".join(lines)

    @staticmethod
    def _apply_variant_filter(data: pd.DataFrame, variant: EstimationVariant) -> pd.DataFrame:
        if variant.filter is None:
            return data
        f = variant.filter
        col = f.column
        if col not in data.columns:
            return data
        if f.operator == FilterOperator.IN:
            return data[data[col].isin(f.values)]
        elif f.operator == FilterOperator.GT:
            return data[data[col] > f.values[0]]
        elif f.operator == FilterOperator.LT:
            return data[data[col] < f.values[0]]
        elif f.operator == FilterOperator.EQ:
            return data[data[col] == f.values[0]]
        return data

    def _run_dml_quick(self, data, treatment, outcome, dag_str) -> Optional[float]:
        try:
            m = dowhy.CausalModel(
                data=data, treatment=treatment, outcome=outcome, graph=dag_str,
            )
            ident = m.identify_effect(proceed_when_unidentifiable=False)
            est = m.estimate_effect(
                ident, method_name="backdoor.econml.dml.DML",
                method_params={
                    "init_params": {
                        "model_y": LGBMRegressor(**LGBM_DEFAULTS),
                        "model_t": LGBMRegressor(**LGBM_DEFAULTS),
                        "model_final": LinearRegression(),
                        "discrete_treatment": False,
                    },
                    "fit_params": {},
                },
            )
            return float(est.value)
        except Exception as e:
            logger.warning(f"Quick DML failed: {e}")
            return None
