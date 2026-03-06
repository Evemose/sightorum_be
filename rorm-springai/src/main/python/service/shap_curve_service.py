"""SHAP dependence curve computation for stability selection runs."""

import joblib
import logging
import numpy as np
import os
import psutil
from concurrent.futures import ThreadPoolExecutor, as_completed
from io import BytesIO
from typing import Any, Optional

logger = logging.getLogger(__name__)


class ShapCurveService:
    """Compute averaged SHAP dependence curves from persisted stability selection models."""

    def __init__(self, db_storage):
        self.db_storage = db_storage

    def compute_curves(
            self,
            run_id: str,
            features: Optional[list[str]] = None,
            n_bins: int = 100,
            n_breakpoints: int = 1,
    ) -> dict[str, Any]:
        run = self.db_storage.load_stability_run(run_id)
        if run is None:
            return None

        problem_type = run["problem_type"]
        feature_columns = run["feature_columns"]
        encoded_columns = run["encoded_columns"]

        # Deserialize encoded data and feature values
        encoded_array = joblib.load(BytesIO(run["encoded_data"]))
        feature_values_dict = joblib.load(BytesIO(run["feature_values"]))

        # Determine which features to process
        target_features = features if features else feature_columns

        # Build column mapping: original feature -> list of encoded column indices
        col_mapping = self._build_column_mapping(feature_columns, encoded_columns)

        # Classify features as numeric or categorical
        feature_types = {}
        for feat in target_features:
            if feat not in col_mapping:
                continue
            indices = col_mapping[feat]
            if len(indices) == 1 and not encoded_columns[indices[0]].startswith(feat + "__"):
                feature_types[feat] = "numeric"
            elif len(indices) == 1 and encoded_columns[indices[0]] == feat:
                feature_types[feat] = "numeric"
            else:
                feature_types[feat] = "categorical"

        # Compute bin edges for numeric features
        bin_edges = {}
        for feat in target_features:
            if feature_types.get(feat) != "numeric":
                continue
            values = feature_values_dict.get(feat)
            if values is None:
                continue
            if np.issubdtype(values.dtype, np.floating):
                valid = values[~np.isnan(values)]
            else:
                valid = values
            valid = valid.astype(np.float64)
            percentiles = np.percentile(valid, np.linspace(0, 100, n_bins + 1))
            edges = np.unique(percentiles)
            bin_edges[feat] = edges

        n_models = self.db_storage.count_stability_run_models(run_id)
        logger.info(f"Computing SHAP curves for {len(target_features)} features across {n_models} models")

        # Decide parallelism: each in-flight model holds ~model + SHAP matrix in memory
        # Estimate per-model memory: encoded_array sample slice * 2 (SHAP output is same shape)
        sample_row = self.db_storage.load_stability_run_model(run_id, 0)
        if sample_row is None:
            return {"run_id": run_id, "problem_type": problem_type, "n_models": 0, "n_bins": n_bins, "curves": []}

        sample_indices_0 = joblib.load(BytesIO(sample_row["sample_indices"]))
        sample_size = len(sample_indices_0)
        bytes_per_model = sample_size * len(encoded_columns) * 4 * 3  # X_sample + shap_values + overhead
        del sample_row, sample_indices_0

        try:
            available_gb = (psutil.virtual_memory().available / (1024 ** 3)) * 0.5
        except Exception:
            available_gb = 2.0

        max_by_memory = max(1, int(available_gb * (1024 ** 3) / max(bytes_per_model, 1)))
        max_by_cpu = max(1, (os.cpu_count() or 2) // 2 - 1)
        max_workers = min(max_by_memory, max_by_cpu, n_models)
        logger.info(f"SHAP parallel: {max_workers} workers "
                    f"(memory allows {max_by_memory}, CPUs allow {max_by_cpu})")

        # Build context shared across workers (read-only)
        ctx = _ShapWorkerContext(
            encoded_array=encoded_array,
            feature_values_dict=feature_values_dict,
            target_features=[f for f in target_features if f in col_mapping],
            col_mapping=col_mapping,
            feature_types=feature_types,
            bin_edges=bin_edges,
        )

        # Submit all models to thread pool
        numeric_feats = [f for f in ctx.target_features if feature_types.get(f) == "numeric"]
        cat_feats = [f for f in ctx.target_features if feature_types.get(f) == "categorical"]

        # Pre-allocate result arrays for numeric features: (n_models, n_bins_per_feat)
        numeric_stacks: dict[str, np.ndarray] = {}
        for feat in numeric_feats:
            edges = bin_edges.get(feat)
            if edges is not None and len(edges) >= 2:
                numeric_stacks[feat] = np.full((n_models, len(edges) - 1), np.nan)

        cat_results_list: dict[str, list] = {f: [None] * n_models for f in cat_feats}

        with ThreadPoolExecutor(max_workers=max_workers, thread_name_prefix="shap-") as executor:
            futures = {}
            for model_idx in range(n_models):
                future = executor.submit(
                    self._process_single_model,
                    self.db_storage, run_id, model_idx, ctx,
                )
                futures[future] = model_idx

            completed = 0
            for future in as_completed(futures):
                model_idx = futures[future]
                try:
                    numeric_curves, cat_dicts = future.result()
                    for feat, curve in numeric_curves.items():
                        if feat in numeric_stacks:
                            numeric_stacks[feat][model_idx] = curve
                    for feat, cat_dict in cat_dicts.items():
                        cat_results_list[feat][model_idx] = cat_dict
                except Exception:
                    logger.error(f"SHAP computation failed for model {model_idx}", exc_info=True)

                completed += 1
                if completed % 10 == 0 or completed == n_models:
                    logger.info(f"  SHAP progress: {completed}/{n_models} models")

        # Aggregate results
        curves_output = []

        for feat in target_features:
            if feat not in col_mapping:
                continue

            if feature_types[feat] == "numeric":
                stacked = numeric_stacks.get(feat)
                if stacked is None:
                    continue

                edges = bin_edges[feat]
                bin_centers = (edges[:-1] + edges[1:]) / 2.0

                mean_shap = np.nanmean(stacked, axis=0)
                std_shap = np.nanstd(stacked, axis=0)
                n_contributing = np.sum(~np.isnan(stacked), axis=0)

                breakpoint_info = self._detect_breakpoints(stacked, bin_centers, n_breakpoints)

                curves_output.append({
                    "feature": feat,
                    "type": "numeric",
                    "bin_centers": bin_centers.tolist(),
                    "mean_shap": mean_shap.tolist(),
                    "std_shap": std_shap.tolist(),
                    "n_models_contributing": n_contributing.tolist(),
                    "breakpoints": breakpoint_info,
                })

            else:
                model_cats = [d for d in cat_results_list.get(feat, []) if d is not None]
                if not model_cats:
                    continue

                all_cats = set()
                for d in model_cats:
                    all_cats.update(d.keys())

                cat_results = []
                for cat in sorted(all_cats):
                    vals = [d.get(cat, np.nan) for d in model_cats]
                    vals_arr = np.array(vals)
                    cat_results.append({
                        "category": cat,
                        "mean_abs_shap": float(np.nanmean(vals_arr)),
                        "std_abs_shap": float(np.nanstd(vals_arr)),
                        "n_models_contributing": int(np.sum(~np.isnan(vals_arr))),
                    })

                cat_results.sort(key=lambda x: -x["mean_abs_shap"])

                curves_output.append({
                    "feature": feat,
                    "type": "categorical",
                    "categories": cat_results,
                })

        return {
            "run_id": run_id,
            "problem_type": problem_type,
            "n_models": n_models,
            "n_bins": n_bins,
            "curves": curves_output,
        }

    @staticmethod
    def _process_single_model(
            db_storage,
            run_id: str,
            model_idx: int,
            ctx: '_ShapWorkerContext',
    ) -> tuple[dict[str, np.ndarray], dict[str, dict[str, float]]]:
        """Process one model: load, compute SHAP, bin. Returns (numeric_curves, cat_dicts)."""
        import shap

        row = db_storage.load_stability_run_model(run_id, model_idx)
        if row is None:
            return {}, {}

        model = joblib.load(BytesIO(row["model_binary"]))
        sample_indices = joblib.load(BytesIO(row["sample_indices"]))
        del row

        X_sample = ctx.encoded_array[sample_indices]

        explainer = shap.TreeExplainer(model)
        shap_values = explainer.shap_values(X_sample)
        del model, explainer

        # Handle classification output
        if isinstance(shap_values, list):
            if len(shap_values) == 2:
                shap_values = shap_values[1]
            else:
                shap_values = np.mean(np.abs(np.array(shap_values)), axis=0)

        numeric_curves: dict[str, np.ndarray] = {}
        cat_dicts: dict[str, dict[str, float]] = {}

        for feat in ctx.target_features:
            indices = ctx.col_mapping[feat]

            if ctx.feature_types[feat] == "numeric":
                feat_shap = shap_values[:, indices].sum(axis=1) if len(indices) > 1 else shap_values[:, indices[0]]

                orig_values = ctx.feature_values_dict.get(feat)
                if orig_values is None:
                    continue
                sample_values = orig_values[sample_indices]

                edges = ctx.bin_edges.get(feat)
                if edges is None or len(edges) < 2:
                    continue

                actual_n_bins = len(edges) - 1
                bin_idx = np.digitize(sample_values, edges[1:-1])  # 0..n_bins-1
                # Vectorized binning via bincount
                sums = np.bincount(bin_idx, weights=feat_shap, minlength=actual_n_bins)[:actual_n_bins]
                counts = np.bincount(bin_idx, minlength=actual_n_bins)[:actual_n_bins].astype(np.float64)
                curve = np.full(actual_n_bins, np.nan)
                nonzero = counts > 0
                curve[nonzero] = sums[nonzero] / counts[nonzero]

                numeric_curves[feat] = curve

            else:
                abs_shap = np.abs(shap_values[:, indices]).sum(axis=1) if len(indices) > 1 else np.abs(
                    shap_values[:, indices[0]])

                orig_values = ctx.feature_values_dict.get(feat)
                if orig_values is None:
                    continue
                sample_values = np.array([str(v) for v in orig_values[sample_indices]])

                cat_shap = {}
                for cat in np.unique(sample_values):
                    mask = sample_values == cat
                    if mask.any():
                        cat_shap[cat] = float(np.mean(abs_shap[mask]))
                cat_dicts[feat] = cat_shap

        return numeric_curves, cat_dicts

    @staticmethod
    def _build_column_mapping(
            feature_columns: list[str],
            encoded_columns: list[str],
    ) -> dict[str, list[int]]:
        """Map each original feature to its encoded column indices."""
        mapping: dict[str, list[int]] = {f: [] for f in feature_columns}

        for idx, enc_col in enumerate(encoded_columns):
            matched = False
            for feat in feature_columns:
                prefix = feat + "__"
                if enc_col == feat or enc_col.startswith(prefix):
                    mapping[feat].append(idx)
                    matched = True
                    break
            if not matched:
                if enc_col in mapping:
                    mapping[enc_col].append(idx)

        return {k: v for k, v in mapping.items() if v}

    @staticmethod
    def _detect_breakpoints(
            stacked_curves: np.ndarray,
            bin_centers: np.ndarray,
            n_breakpoints: int,
    ) -> dict[str, Any]:
        """Run Muggeo breakpoint detection on each model's curve, report median +/- IQR."""
        try:
            import piecewise_regression
        except ImportError:
            return {"error": "piecewise-regression not installed"}

        all_breakpoints = []

        for model_idx in range(stacked_curves.shape[0]):
            curve = stacked_curves[model_idx]
            valid = ~np.isnan(curve)
            x = bin_centers[valid]
            y = curve[valid]

            if len(x) < 5:
                continue

            try:
                pw = piecewise_regression.Fit(x, y, n_breakpoints=n_breakpoints)
                if pw.get_results()["converged"]:
                    bps = pw.get_results()["estimates"]["breakpoint1"]["estimate"]
                    all_breakpoints.append(bps)
            except Exception:
                continue

        if not all_breakpoints:
            return {
                "breakpoint_median": None,
                "breakpoint_iqr_25": None,
                "breakpoint_iqr_75": None,
                "breakpoints_converged": 0,
                "breakpoints_total": stacked_curves.shape[0],
            }

        bp_arr = np.array(all_breakpoints)
        return {
            "breakpoint_median": float(np.median(bp_arr)),
            "breakpoint_iqr_25": float(np.percentile(bp_arr, 25)),
            "breakpoint_iqr_75": float(np.percentile(bp_arr, 75)),
            "breakpoints_converged": len(all_breakpoints),
            "breakpoints_total": stacked_curves.shape[0],
        }


class _ShapWorkerContext:
    """Read-only context shared across SHAP worker threads."""
    __slots__ = ("encoded_array", "feature_values_dict", "target_features",
                 "col_mapping", "feature_types", "bin_edges")

    def __init__(self, encoded_array, feature_values_dict, target_features,
                 col_mapping, feature_types, bin_edges):
        self.encoded_array = encoded_array
        self.feature_values_dict = feature_values_dict
        self.target_features = target_features
        self.col_mapping = col_mapping
        self.feature_types = feature_types
        self.bin_edges = bin_edges
