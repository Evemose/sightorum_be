"""Stability selection analysis service."""

import joblib
import logging
import math
import numpy as np
import pandas as pd
import sqlglot
import uuid as uuid_mod
from core.exceptions import ModelTrainingError, ValidationError
from datasource.interface import Datasource
from dto.requests import StabilitySelectionRequest
from io import BytesIO
from lightgbm import LGBMClassifier, LGBMRegressor
from sklearn.linear_model import ElasticNet, LinearRegression, LogisticRegression
from sklearn.preprocessing import LabelEncoder
from sqlglot import exp
from typing import Any, Callable, Optional

logger = logging.getLogger(__name__)


class StabilitySelectionService:
    """Run stability selection style feature analysis across multiple model families."""

    def __init__(self, db_storage=None, worker_pool=None):
        self.db_storage = db_storage
        self.worker_pool = worker_pool

    @staticmethod
    def _add_random_sampling(sql: str, limit: int, seed: int) -> str:
        """
        Add random sampling to SQL query using sqlglot for robust parsing.

        Uses a CTE to call setseed() for reproducibility, then
        ORDER BY RANDOM() + LIMIT for sampling. Produces a single SQL statement
        compatible with estimate_size() and other query wrappers.
        """
        try:
            # Parse SQL - auto-detect dialect
            parsed = sqlglot.parse_one(sql, dialect="postgres")

            # Wrap in subquery if it already has LIMIT/ORDER BY
            if parsed.args.get("limit") or parsed.args.get("order"):
                parsed = exp.select("*").from_(parsed.subquery("base_query"))

            # Add ORDER BY RANDOM() and LIMIT
            parsed = (
                parsed
                .order_by(exp.Anonymous(this="RANDOM", expressions=[]))
                .limit(limit)
            )

            # Wrap with a CTE that calls setseed() for reproducibility
            # CTE: WITH _seed AS (SELECT setseed(<normalized>))
            # Main: SELECT ... FROM ..., _seed ORDER BY RANDOM() LIMIT N
            seed_normalized = seed / 2147483647.0  # Normalize to [0,1] for setseed
            result_sql = (
                    f"WITH _seed AS (SELECT setseed({seed_normalized})) "
                    + parsed.sql(dialect="postgres")
            )

            return result_sql

        except Exception as e:
            logger.warning(f"Could not parse SQL with sqlglot: {e}. Using fallback wrapping.")
            # Fallback: CTE-based wrapping (still single statement)
            seed_normalized = seed / 2147483647.0
            return (
                f"WITH _seed AS (SELECT setseed({seed_normalized})) "
                f"SELECT * FROM ({sql.rstrip(';')}) AS base_query "
                f"ORDER BY RANDOM() "
                f"LIMIT {limit}"
            )

    def analyze(
            self,
            request: StabilitySelectionRequest,
            datasource: Datasource,
            progress_callback: Optional[Callable[[float, str], None]] = None,
    ) -> dict[str, Any]:
        """Execute stability selection analysis and return a structured summary."""
        logger.info(f"Starting stability selection analysis with {request.bootstrap_runs} bootstrap runs")
        validation_result = request.validate()
        validation_result.raise_if_invalid()

        if progress_callback:
            progress_callback(0.05, "Validating request")

        # Estimate dataset size and apply SQL-level sampling if needed
        target_max_rows = 1_000_000
        sql_query = request.datasource.sql

        try:
            size_estimate = datasource.estimate_size(sql_query, request.datasource.bind_variables)
            logger.info(f"Dataset size estimate: {size_estimate.estimated_rows} rows")

            if size_estimate.estimated_rows > target_max_rows:
                logger.info(f"Large dataset detected ({size_estimate.estimated_rows} rows). "
                            f"Applying SQL-level sampling to ~{target_max_rows} rows")

                # Use sqlglot to parse and add LIMIT + ORDER BY RANDOM() for sampling
                sql_query = self._add_random_sampling(
                    sql_query,
                    limit=target_max_rows,
                    seed=request.random_state
                )
                logger.info(f"Applied SQL sampling: LIMIT {target_max_rows} with random ordering")
        except Exception as e:
            logger.warning(f"Could not estimate/sample at SQL level: {e}. Will fetch full dataset.")

        if progress_callback:
            progress_callback(0.10, "Fetching data from datasource")

        logger.info("Fetching data from datasource")
        data_result = datasource.fetch(
            sql_query,
            request.datasource.bind_variables,
        )
        df = data_result.dataframe
        logger.info(f"Fetched {len(df)} rows with {len(df.columns)} columns")

        if progress_callback:
            progress_callback(0.20, f"Fetched {len(df)} rows, encoding features")

        if request.target_column not in df.columns:
            raise ValidationError(
                message=f"Target column '{request.target_column}' not found in dataset",
                field_errors={
                    "target_column": [
                        f"Available columns: {', '.join(df.columns)}"
                    ]
                },
            )

        control_features = request.control_features or []
        feature_columns = self._resolve_feature_columns(
            df.columns, request.target_column, request.feature_columns, request.control_features,
        )
        logger.info(f"Using {len(feature_columns)} feature columns")
        if not feature_columns:
            raise ValidationError(
                message="No feature columns available for stability selection",
                field_errors={
                    "feature_columns": [
                        "Provide feature_columns or ensure the dataset includes columns besides target_column"
                    ]
                },
            )

        missing_columns = [column for column in feature_columns if column not in df.columns]
        if missing_columns:
            raise ValidationError(
                message="Feature columns not found in dataset",
                field_errors={
                    "feature_columns": [
                        f"Missing columns: {', '.join(missing_columns)}"
                    ]
                },
            )

        if control_features:
            missing_controls = [c for c in control_features if c not in df.columns]
            if missing_controls:
                raise ValidationError(
                    message="Control features not found in dataset",
                    field_errors={
                        "control_features": [
                            f"Missing columns: {', '.join(missing_controls)}"
                        ]
                    },
                )

        select_columns = feature_columns + [request.target_column]
        if control_features:
            select_columns = list(dict.fromkeys(select_columns + control_features))
        analysis_df = df.select(select_columns).to_pandas()
        analysis_df = analysis_df.dropna(subset=[request.target_column]).reset_index(drop=True)
        logger.info(f"Analysis dataset: {len(analysis_df)} rows after removing null targets")

        minimum_rows = max(20, request.bootstrap_runs)
        if len(analysis_df) < minimum_rows:
            raise ModelTrainingError.insufficient_data(
                "stability_selection",
                len(analysis_df),
                minimum_rows,
            )

        feature_frame = analysis_df[feature_columns].copy()
        target_series = analysis_df[request.target_column].copy()

        problem_type = request.problem_type or self._infer_problem_type(target_series)
        logger.info(f"Problem type: {problem_type}")
        y_values, target_metadata = self._prepare_target(target_series, problem_type)

        warnings: list[str] = []

        logger.info("Encoding features")
        object_cols = feature_frame.select_dtypes(include=["object", "string"]).columns.tolist()
        if object_cols:
            logger.info(f"Columns with object dtype (will attempt numeric coercion): {object_cols}")
            for col in object_cols:
                nunique = feature_frame[col].nunique()
                logger.info(f"  {col}: {nunique} unique values, sample: {feature_frame[col].dropna().head(3).tolist()}")
        encoded_frame = self._encode_features(feature_frame)
        encoded_columns = encoded_frame.columns.tolist()
        if len(encoded_columns) > request.max_encoded_dimensions:
            raise ValidationError(
                message=f"Encoded feature space has {len(encoded_columns)} dimensions, which exceeds the maximum of {request.max_encoded_dimensions}",
                field_errors={
                    "feature_columns": [
                        f"Reduce the number of feature columns. Check for possible noise-features leakage like string ids."
                        f"If query is correct, increase max_encoded_dimensions"
                    ]
                },
            )
        logger.info(f"Encoded to {len(encoded_columns)} columns")

        if progress_callback:
            progress_callback(0.30, f"Pre-processing {len(encoded_columns)} encoded features")

        # Convert to numpy array for memory-efficient sharing across threads
        encoded_array = encoded_frame.to_numpy(dtype=np.float32)
        del encoded_frame  # Free the pandas copy

        # Pre-impute NaN with column medians in-place — avoids per-worker SimpleImputer overhead
        logger.info("Pre-imputing missing values")
        col_medians = np.nanmedian(encoded_array, axis=0)
        nan_mask = np.isnan(encoded_array)
        if nan_mask.any():
            # Broadcast medians into NaN positions
            col_indices = np.where(nan_mask)[1]
            encoded_array[nan_mask] = col_medians[col_indices]
            logger.info(f"Imputed {nan_mask.sum()} NaN values")

        if progress_callback:
            progress_callback(0.32, f"Imputed missing values in {len(encoded_columns)} columns")

        # Pre-scale in-place — avoids per-worker StandardScaler overhead
        logger.info("Pre-scaling features")
        col_means = encoded_array.mean(axis=0)
        col_stds = encoded_array.std(axis=0)
        col_stds[col_stds == 0] = 1.0  # Prevent division by zero for constant columns
        encoded_array -= col_means
        encoded_array /= col_stds

        if control_features:
            logger.info(f"Residualising against {len(control_features)} control features")
            control_frame = analysis_df[control_features].copy()
            control_encoded_frame = self._encode_features(control_frame)
            control_array = np.array(control_encoded_frame, dtype=np.float32)
            del control_encoded_frame

            ctrl_medians = np.nanmedian(control_array, axis=0)
            ctrl_nan = np.isnan(control_array)
            if ctrl_nan.any():
                control_array[ctrl_nan] = ctrl_medians[np.where(ctrl_nan)[1]]

            ctrl_means = control_array.mean(axis=0)
            ctrl_stds = control_array.std(axis=0)
            ctrl_stds[ctrl_stds == 0] = 1.0
            control_array = (control_array - ctrl_means) / ctrl_stds

            encoded_array, y_values = self._residualise(
                encoded_array, y_values, control_array, problem_type,
                random_state=request.random_state,
            )
            del control_array
            logger.info("Residualisation complete")

        if progress_callback:
            progress_callback(0.34, "Pre-processing complete")

        # Adaptive memory-based row downsampling — if encoded shape is still too large
        memory_budget_gb = self.worker_pool.metrics.memory_budget_bytes / (1024 ** 3)
        array_gb = encoded_array.nbytes / (1024 ** 3)
        # Each worker needs ~2x the sample slice (copy + sklearn internals)
        min_workers = 2
        per_worker_overhead = 2.0
        max_array_gb = memory_budget_gb / (min_workers * per_worker_overhead + 1)  # +1 for the base array

        if array_gb > max_array_gb and len(encoded_array) > 1000:
            target_rows = max(1000, int(len(encoded_array) * (max_array_gb / array_gb)))
            logger.warning(
                f"Encoded array ({array_gb:.2f} GB) exceeds memory budget "
                f"({max_array_gb:.2f} GB for base array). Downsampling to {target_rows} rows."
            )
            downsample_rng = np.random.default_rng(request.random_state)
            keep_indices = downsample_rng.choice(len(encoded_array), size=target_rows, replace=False)
            keep_indices.sort()
            encoded_array = encoded_array[keep_indices]
            y_values = y_values[keep_indices]
            warnings.append(
                f"In-memory downsampling applied: {target_rows} rows kept "
                f"(encoded width {len(encoded_columns)} columns exceeded memory budget)"
            )

        logger.info(f"Pre-processed array: {encoded_array.shape}, "
                    f"memory: {encoded_array.nbytes / (1024 ** 3):.2f} GB")
        if not encoded_columns:
            raise ValidationError(
                message="No usable features after encoding",
                field_errors={
                    "feature_columns": [
                        "All selected features were empty or unsupported"
                    ]
                },
            )

        if request.selection_top_k is None:
            selection_top_k = max(1, min(len(feature_columns), int(math.ceil(math.sqrt(len(feature_columns))))))
        else:
            selection_top_k = min(request.selection_top_k, len(feature_columns))

        logger.info("Building model specifications")
        model_specs, model_warnings = self._build_model_specs(
            problem_type=problem_type,
            random_state=request.random_state,
            n_classes=target_metadata.get("class_count"),
        )
        warnings.extend(model_warnings)
        logger.info(f"Built {len(model_specs)} model families: {[spec[0] for spec in model_specs]}")

        if progress_callback:
            progress_callback(0.35, f"Building {len(model_specs)} model families")

        if not model_specs:
            raise ModelTrainingError(
                model_type="stability_selection",
                message="No model families are available for the requested analysis",
                details={"warnings": warnings},
            )

        rng = np.random.default_rng(request.random_state)
        sample_size = max(2, int(len(encoded_array) * request.sample_fraction))

        # Pre-generate all sample indices to ensure reproducibility
        logger.info(f"Pre-generating {request.bootstrap_runs * len(model_specs)} sample sets")
        all_sample_indices = []
        for _ in range(request.bootstrap_runs * len(model_specs)):
            sample_indices = self._sample_indices(
                row_count=len(encoded_array),
                sample_size=sample_size,
                rng=rng,
                y_values=y_values,
                problem_type=problem_type,
            )
            all_sample_indices.append(sample_indices)

        model_results = []
        captured_lgbm_models: list[tuple[Any, np.ndarray]] = []

        bytes_per_sample = encoded_array.nbytes * (sample_size / len(encoded_array))
        bytes_per_task = int(bytes_per_sample * 1.5)
        logger.info(f"Per-task memory estimate: {bytes_per_task / (1024 ** 3):.2f} GB")

        capture_models = self.db_storage is not None
        total_iterations = request.bootstrap_runs * len(model_specs)
        global_completed = 0

        sample_idx_offset = 0
        for family_idx, (model_name, factory, extractor) in enumerate(model_specs):
            is_lgbm = model_name == "lightgbm"
            use_capturing = is_lgbm and capture_models

            logger.info(f"Starting {request.bootstrap_runs} runs for model: {model_name}")
            futures = []
            for run_idx in range(request.bootstrap_runs):
                sample_indices = all_sample_indices[sample_idx_offset + run_idx]
                iteration_fn = self._run_bootstrap_iteration_capturing if use_capturing else self._run_bootstrap_iteration
                future = self.worker_pool.submit(
                    bytes_per_task,
                    lambda
                        _fn=iteration_fn, _mn=model_name, _f=factory,
                        _e=extractor, _si=sample_indices:
                    _fn(
                        _mn, _f, _e, encoded_array, encoded_columns,
                        y_values, _si, feature_columns, selection_top_k,
                    ),
                )
                futures.append((future, sample_indices))

            runs = []
            for idx, (future, sample_indices) in enumerate(futures):
                try:
                    result = future.result()
                    if use_capturing:
                        rankings, fitted_model = result
                        runs.append(rankings)
                        captured_lgbm_models.append((fitted_model, sample_indices))
                    else:
                        runs.append(result)
                    global_completed += 1
                    if (idx + 1) % 10 == 0 or idx == len(futures) - 1:
                        logger.info(f"  Completed {idx + 1}/{len(futures)} runs for {model_name}")
                    if progress_callback:
                        progress_callback(
                            0.35 + (global_completed / total_iterations) * 0.50,
                            f"{model_name}: {idx + 1}/{request.bootstrap_runs} iterations",
                        )
                except Exception as e:
                    logger.error(f"Error in bootstrap run {idx} for {model_name}: {e}", exc_info=True)
                    raise

            sample_idx_offset += request.bootstrap_runs
            logger.info(f"Summarizing results for {model_name}")
            model_results.append(self._summarize_model_runs(model_name, runs, feature_columns))

        # Persist LightGBM models for SHAP analysis
        if progress_callback:
            progress_callback(0.85, "Persisting models")

        run_id = None
        if captured_lgbm_models and self.db_storage is not None:
            run_id = str(uuid_mod.uuid4())
            logger.info(f"Persisting {len(captured_lgbm_models)} LightGBM models under run_id={run_id}")

            # Serialize encoded_data and feature_values
            encoded_buf = BytesIO()
            joblib.dump(encoded_array, encoded_buf, compress=3)
            encoded_data_bytes = encoded_buf.getvalue()

            # Build original-scale feature values dict
            feature_values_dict = {}
            for col in feature_columns:
                if col in feature_frame.columns:
                    feature_values_dict[col] = feature_frame[col].to_numpy()
            fv_buf = BytesIO()
            joblib.dump(feature_values_dict, fv_buf, compress=3)
            feature_values_bytes = fv_buf.getvalue()

            self.db_storage.save_stability_run(
                run_id=run_id,
                problem_type=problem_type,
                feature_columns=feature_columns,
                encoded_columns=encoded_columns,
                bootstrap_runs=request.bootstrap_runs,
                encoded_data=encoded_data_bytes,
                feature_values=feature_values_bytes,
                control_features=control_features if control_features else None,
            )

            # Serialize and batch-insert models
            model_rows = []
            total_models = len(captured_lgbm_models)
            for idx, (model, sample_idx) in enumerate(captured_lgbm_models):
                m_buf = BytesIO()
                joblib.dump(model, m_buf, compress=3)
                si_buf = BytesIO()
                joblib.dump(sample_idx, si_buf, compress=3)
                model_rows.append((idx, m_buf.getvalue(), si_buf.getvalue()))
                if progress_callback:
                    progress_callback(
                        0.85 + (idx + 1) / total_models * 0.04,
                        f"Serializing model {idx + 1}/{total_models}",
                    )

            self.db_storage.save_stability_run_models_batch(run_id, model_rows)
            logger.info(f"Persisted run {run_id} with {len(model_rows)} models")

            if progress_callback:
                progress_callback(0.895, f"Persisted {len(model_rows)} models to storage")

        if progress_callback:
            progress_callback(0.90, "Computing correlation groups and consensus")

        logger.info("Finding correlated feature groups")
        correlation_groups = self._find_correlated_groups(feature_frame, request.correlation_threshold)
        logger.info(f"Found {len(correlation_groups)} correlated groups")

        logger.info("Building consensus across models")
        consensus = self._build_consensus(model_results, feature_columns, correlation_groups)

        if progress_callback:
            progress_callback(0.95, "Finalizing results")

        logger.info("Stability selection analysis complete")
        result = {
            "methodology": {
                "name": "stability_selection",
                "reference": 'Meinshausen & Buhlmann (2010), "Stability Selection." Journal of the Royal Statistical Society.',
                "bootstrap_runs": request.bootstrap_runs,
                "sample_fraction": request.sample_fraction,
                "selection_top_k": selection_top_k,
                "correlation_threshold": request.correlation_threshold,
            },
            "dataset": {
                "rows": int(len(analysis_df)),
                "original_feature_count": len(feature_columns),
                "encoded_feature_count": len(encoded_columns),
                "target_column": request.target_column,
                "problem_type": problem_type,
                "feature_columns": feature_columns,
            },
            "target_metadata": target_metadata,
            "warnings": warnings + data_result.warnings,
            "correlation_groups": correlation_groups,
            "model_results": model_results,
            "consensus": consensus,
        }
        if control_features:
            result["methodology"]["residualisation"] = {
                "method": "frisch_waugh_lovell",
                "control_features": control_features,
                "target_residualised": problem_type == "regression",
            }
            result["dataset"]["control_features"] = control_features
        if run_id:
            result["run_id"] = run_id
        return result

    @staticmethod
    def _run_bootstrap_iteration(
            model_name: str,
            factory: Callable[[], Any],
            extractor: Callable[[Any, list[str]], dict[str, float]],
            encoded_array: np.ndarray,
            encoded_columns: list[str],
            y_values: np.ndarray,
            sample_indices: np.ndarray,
            feature_columns: list[str],
            selection_top_k: int,
    ) -> dict[str, dict[str, float | bool]]:
        """Run a single bootstrap iteration for a model.

        Uses numpy array slicing (view, not copy) for memory efficiency.
        Sklearn transformers don't mutate inputs, so sharing is safe.
        """
        try:
            # Use array view - no copy needed since sklearn doesn't mutate
            X_sample = encoded_array[sample_indices]
            y_sample = y_values[sample_indices]

            estimator = factory()
            estimator.fit(X_sample, y_sample)

            encoded_scores = extractor(estimator, encoded_columns)
            return StabilitySelectionService._rank_features(encoded_scores, feature_columns, selection_top_k)
        except Exception as e:
            logger.error(f"Error in bootstrap iteration for {model_name}: {e}", exc_info=True)
            raise

    @staticmethod
    def _run_bootstrap_iteration_capturing(
            model_name: str,
            factory: Callable[[], Any],
            extractor: Callable[[Any, list[str]], dict[str, float]],
            encoded_array: np.ndarray,
            encoded_columns: list[str],
            y_values: np.ndarray,
            sample_indices: np.ndarray,
            feature_columns: list[str],
            selection_top_k: int,
    ) -> tuple[dict[str, dict[str, float | bool]], Any]:
        """Same as _run_bootstrap_iteration but also returns the fitted model."""
        try:
            X_sample = encoded_array[sample_indices]
            y_sample = y_values[sample_indices]

            estimator = factory()
            estimator.fit(X_sample, y_sample)

            encoded_scores = extractor(estimator, encoded_columns)
            rankings = StabilitySelectionService._rank_features(encoded_scores, feature_columns, selection_top_k)
            return rankings, estimator
        except Exception as e:
            logger.error(f"Error in capturing bootstrap iteration for {model_name}: {e}", exc_info=True)
            raise

    @staticmethod
    def _sample_indices(
            row_count: int,
            sample_size: int,
            rng: np.random.Generator,
            y_values: np.ndarray,
            problem_type: str,
    ) -> np.ndarray:
        for _ in range(20):
            sample_indices = rng.choice(row_count, size=sample_size, replace=False)
            if problem_type != "classification" or len(np.unique(y_values[sample_indices])) > 1:
                return sample_indices

        raise ModelTrainingError(
            model_type="stability_selection",
            message="Could not draw a classification subsample containing at least two target classes",
        )

    @staticmethod
    def _resolve_feature_columns(
            available_columns: list[str],
            target_column: str,
            feature_columns: Optional[list[str]],
            control_features: Optional[list[str]] = None,
    ) -> list[str]:
        if feature_columns is None:
            exclude = {target_column}
            if control_features:
                exclude.update(control_features)
            return [column for column in available_columns if column not in exclude]
        return feature_columns

    @staticmethod
    def _residualise(
            X: np.ndarray,
            y: np.ndarray,
            C: np.ndarray,
            problem_type: str,
            random_state: int = 42,
            n_splits: int = 5,
    ) -> tuple[np.ndarray, np.ndarray]:
        """Partial out control variables using LightGBM with k-fold cross-validation.

        For regression: residualise both X and y.
        For classification: residualise only X (y is categorical).

        Uses out-of-fold predictions to avoid overfitting the residualisation step,
        and LightGBM to capture nonlinear control-variable effects.
        """
        from sklearn.model_selection import KFold

        n_splits = min(n_splits, len(C))
        if n_splits < 2:
            return X, y

        kf = KFold(n_splits=n_splits, shuffle=True, random_state=random_state)
        folds = list(kf.split(C))

        def _lgbm():
            return LGBMRegressor(
                n_estimators=100, learning_rate=0.1,
                max_depth=4, verbose=-1, random_state=random_state,
            )

        X_resid = np.empty_like(X)
        for col_idx in range(X.shape[1]):
            x_col = X[:, col_idx]
            preds = np.zeros_like(x_col)
            for train_idx, val_idx in folds:
                model = _lgbm()
                model.fit(C[train_idx], x_col[train_idx])
                preds[val_idx] = model.predict(C[val_idx])
            X_resid[:, col_idx] = x_col - preds

        if problem_type == "regression":
            y_preds = np.zeros(len(y), dtype=np.float64)
            for train_idx, val_idx in folds:
                model = _lgbm()
                model.fit(C[train_idx], y[train_idx])
                y_preds[val_idx] = model.predict(C[val_idx])
            y_resid = y - y_preds
        else:
            y_resid = y

        return X_resid, y_resid

    @staticmethod
    def _infer_problem_type(target_series: pd.Series) -> str:
        if pd.api.types.is_numeric_dtype(target_series):
            unique_values = target_series.nunique(dropna=True)
            if unique_values <= 10:
                return "classification"
            return "regression"
        return "classification"

    @staticmethod
    def _prepare_target(target_series: pd.Series, problem_type: str) -> tuple[np.ndarray, dict[str, Any]]:
        if problem_type == "regression":
            numeric_target = pd.to_numeric(target_series, errors="coerce")
            if numeric_target.isna().any():
                raise ValidationError(
                    message="Regression target must be numeric",
                    field_errors={
                        "target_column": [
                            "Target contains non-numeric values that cannot be used for regression"
                        ]
                    },
                )
            return numeric_target.to_numpy(dtype=float), {
                "target_dtype": str(target_series.dtype),
                "class_count": None,
            }

        encoder = LabelEncoder()
        encoded = encoder.fit_transform(target_series.astype(str))
        return encoded.astype(int), {
            "target_dtype": str(target_series.dtype),
            "class_count": int(len(encoder.classes_)),
            "classes": encoder.classes_.tolist(),
        }

    @staticmethod
    def _encode_features(feature_frame: pd.DataFrame) -> pd.DataFrame:
        safe_frame = feature_frame.copy()

        # First, coerce bool columns to int
        for column in safe_frame.columns:
            if pd.api.types.is_bool_dtype(safe_frame[column]):
                safe_frame[column] = safe_frame[column].astype(int)

        # Attempt to convert object columns to numeric — many come through as
        # strings from the DB driver (e.g. Decimal, mixed-None inference).
        # Without this, get_dummies one-hot encodes every distinct numeric
        # value (e.g. 3,000 temperatures → 3,000 dummy columns).
        object_cols = safe_frame.select_dtypes(include=["object", "string"]).columns.tolist()
        for column in object_cols:
            converted = pd.to_numeric(safe_frame[column], errors="coerce")
            # If >50% of non-null values converted successfully, treat as numeric
            non_null = safe_frame[column].notna().sum()
            if non_null > 0 and converted.notna().sum() / non_null > 0.5:
                logger.info(f"Coerced column '{column}' from object to numeric "
                            f"({converted.notna().sum()}/{non_null} values converted)")
                safe_frame[column] = converted

        # Only dummy-encode remaining object/category/string columns
        categorical_cols = safe_frame.select_dtypes(include=["object", "string", "category"]).columns.tolist()
        if categorical_cols:
            cardinalities = {col: safe_frame[col].nunique() for col in categorical_cols}
            logger.info(f"One-hot encoding {len(categorical_cols)} categorical columns: "
                        f"{cardinalities}")
            encoded = pd.get_dummies(
                safe_frame,
                columns=categorical_cols,
                prefix_sep="__",
                drop_first=False,
                dummy_na=False,
            )
        else:
            encoded = safe_frame

        return encoded.apply(pd.to_numeric, errors="coerce")

    def _build_model_specs(
            self,
            problem_type: str,
            random_state: int,
            n_classes: Optional[int],
    ) -> tuple[list[tuple[str, Callable[[], Any], Callable[[Any, list[str]], dict[str, float]]]], list[str]]:
        warnings: list[str] = []
        model_specs: list[tuple[str, Callable[[], Any], Callable[[Any, list[str]], dict[str, float]]]] = []

        model_specs.append(
            ("linear", self._build_linear_factory(problem_type, random_state), self._extract_pipeline_coefficients))

        model_specs.append(
            ("elastic_net", self._build_elastic_factory(problem_type, random_state),
             self._extract_pipeline_coefficients)
        )

        if problem_type == "regression":
            model_specs.append(
                (
                    "lightgbm",
                    lambda: LGBMRegressor(
                        n_estimators=200,
                        learning_rate=0.05,
                        subsample=0.8,
                        colsample_bytree=0.8,
                        random_state=random_state,
                        verbose=-1,
                    ),
                    self._extract_tree_importances,
                )
            )
        else:
            model_specs.append(
                (
                    "lightgbm",
                    lambda: LGBMClassifier(
                        n_estimators=200,
                        learning_rate=0.05,
                        subsample=0.8,
                        colsample_bytree=0.8,
                        random_state=random_state,
                        verbose=-1,
                    ),
                    self._extract_tree_importances,
                )
            )

        return model_specs, warnings

    @staticmethod
    def _build_linear_factory(problem_type: str, random_state: int) -> Callable[[], Any]:
        """Build linear model factory. Data is already imputed and scaled."""
        if problem_type == "regression":
            return lambda: LinearRegression()

        return lambda: LogisticRegression(max_iter=5000, solver="lbfgs", random_state=random_state)

    @staticmethod
    def _build_elastic_factory(problem_type: str, random_state: int) -> Callable[[], Any]:
        """Build elastic net model factory. Data is already imputed and scaled."""
        if problem_type == "regression":
            return lambda: ElasticNet(alpha=0.05, l1_ratio=0.5, max_iter=5000, random_state=random_state)

        return lambda: LogisticRegression(
            penalty="elasticnet",
            l1_ratio=0.5,
            solver="saga",
            max_iter=5000,
            random_state=random_state,
        )

    @staticmethod
    def _extract_pipeline_coefficients(model: Any, encoded_columns: list[str]) -> dict[str, float]:
        """Extract coefficients from a bare estimator or Pipeline-wrapped estimator."""
        if hasattr(model, "named_steps"):
            estimator = model.named_steps["estimator"]
        else:
            estimator = model
        return StabilitySelectionService._coefficients_by_encoded_feature(estimator, encoded_columns)

    @staticmethod
    def _extract_tree_importances(model: Any, encoded_columns: list[str]) -> dict[str, float]:
        importances = getattr(model, "feature_importances_", None)
        if importances is None:
            raise ModelTrainingError.training_failed(
                "stability_selection",
                f"Model '{type(model).__name__}' does not expose feature_importances_",
            )
        return {
            column: float(value)
            for column, value in zip(encoded_columns, importances)
        }

    @staticmethod
    def _raw_coefficients(estimator: Any) -> np.ndarray:
        coefficients = getattr(estimator, "coef_", None)
        if coefficients is None:
            raise ModelTrainingError.training_failed(
                "stability_selection",
                f"Model '{type(estimator).__name__}' does not expose coefficients",
            )
        coefficients = np.asarray(coefficients)
        if coefficients.ndim == 1:
            return np.abs(coefficients)
        return np.mean(np.abs(coefficients), axis=0)

    @staticmethod
    def _coefficients_by_encoded_feature(estimator: Any, columns: list[str]) -> dict[str, float]:
        coefficients = StabilitySelectionService._raw_coefficients(estimator)
        return {
            column: float(value)
            for column, value in zip(columns, coefficients)
        }

    @staticmethod
    def _rank_features(
            encoded_scores: dict[str, float],
            feature_columns: list[str],
            selection_top_k: int,
    ) -> dict[str, dict[str, float | bool]]:
        base_scores = {feature: 0.0 for feature in feature_columns}
        for encoded_feature, score in encoded_scores.items():
            base_feature = encoded_feature.split("__", 1)[0]
            if base_feature in base_scores:
                base_scores[base_feature] += float(score)

        ordered_features = sorted(
            feature_columns,
            key=lambda feature: (-base_scores[feature], feature),
        )
        rank_map = {feature: index + 1 for index, feature in enumerate(ordered_features)}

        return {
            feature: {
                "score": float(base_scores[feature]),
                "rank": float(rank_map[feature]),
                "selected": bool(base_scores[feature] > 0 and rank_map[feature] <= selection_top_k),
            }
            for feature in feature_columns
        }

    @staticmethod
    def _summarize_model_runs(
            model_name: str,
            runs: list[dict[str, dict[str, float | bool]]],
            feature_columns: list[str],
    ) -> dict[str, Any]:
        feature_details = []
        for feature in feature_columns:
            ranks = np.array([float(run[feature]["rank"]) for run in runs], dtype=float)
            scores = np.array([float(run[feature]["score"]) for run in runs], dtype=float)
            selections = np.array([1.0 if run[feature]["selected"] else 0.0 for run in runs], dtype=float)
            feature_details.append(
                {
                    "feature": feature,
                    "selection_frequency": float(selections.mean()),
                    "mean_rank": float(ranks.mean()),
                    "median_rank": float(np.median(ranks)),
                    "rank_std": float(ranks.std()),
                    "mean_importance": float(scores.mean()),
                }
            )

        feature_details.sort(
            key=lambda item: (
                -item["selection_frequency"],
                item["mean_rank"],
                -item["mean_importance"],
                item["feature"],
            )
        )

        return {
            "model_family": model_name,
            "top_features": feature_details[:10],
            "feature_details": feature_details,
            "robust_features": [
                item["feature"]
                for item in feature_details
                if item["selection_frequency"] >= 0.8
            ],
        }

    @staticmethod
    def _find_correlated_groups(
            feature_frame: pd.DataFrame,
            threshold: float,
    ) -> list[dict[str, Any]]:
        if len(feature_frame.columns) < 2:
            return []

        prepared = pd.DataFrame(index=feature_frame.index)
        for column in feature_frame.columns:
            series = feature_frame[column]
            if pd.api.types.is_numeric_dtype(series) or pd.api.types.is_bool_dtype(series):
                numeric = pd.to_numeric(series, errors="coerce")
            else:
                filled = series.fillna("__missing__").astype(str)
                numeric = pd.Series(pd.factorize(filled)[0], index=series.index, dtype=float)

            if numeric.isna().all():
                prepared[column] = 0.0
            else:
                prepared[column] = numeric.fillna(float(numeric.median()))

        correlation = prepared.corr().abs().fillna(0.0)
        adjacency = {column: set() for column in prepared.columns}

        for left_index, left in enumerate(prepared.columns):
            for right in prepared.columns[left_index + 1:]:
                value = float(correlation.loc[left, right])
                if value > threshold:
                    adjacency[left].add(right)
                    adjacency[right].add(left)

        visited = set()
        groups = []
        for column in prepared.columns:
            if column in visited or not adjacency[column]:
                continue

            stack = [column]
            component = set()
            while stack:
                current = stack.pop()
                if current in visited:
                    continue
                visited.add(current)
                component.add(current)
                stack.extend(adjacency[current] - visited)

            if len(component) < 2:
                continue

            members = sorted(component)
            max_correlation = 0.0
            for left_index, left in enumerate(members):
                for right in members[left_index + 1:]:
                    max_correlation = max(max_correlation, float(correlation.loc[left, right]))

            groups.append(
                {
                    "features": members,
                    "max_pairwise_correlation": round(max_correlation, 4),
                    "interpretation": "One of these matters; determine which before assigning individual credit.",
                }
            )

        groups.sort(key=lambda item: (-item["max_pairwise_correlation"], item["features"]))
        return groups

    @staticmethod
    def _build_consensus(
            model_results: list[dict[str, Any]],
            feature_columns: list[str],
            correlation_groups: list[dict[str, Any]],
    ) -> dict[str, Any]:
        by_model = {
            model_result["model_family"]: {
                item["feature"]: item
                for item in model_result["feature_details"]
            }
            for model_result in model_results
        }

        feature_ranking = []
        high_rank_threshold = max(3.0, len(feature_columns) / 3)
        for feature in feature_columns:
            entries = [model_features[feature] for model_features in by_model.values()]
            feature_ranking.append(
                {
                    "feature": feature,
                    "average_rank": float(np.mean([entry["mean_rank"] for entry in entries])),
                    "average_selection_frequency": float(np.mean([entry["selection_frequency"] for entry in entries])),
                    "models_above_0_8": int(sum(1 for entry in entries if entry["selection_frequency"] >= 0.8)),
                    "robustly_important": bool(all(entry["mean_rank"] <= high_rank_threshold for entry in entries)),
                }
            )

        feature_ranking.sort(
            key=lambda item: (
                -item["average_selection_frequency"],
                item["average_rank"],
                item["feature"],
            )
        )

        group_lookup = {}
        for group_index, group in enumerate(correlation_groups):
            for feature in group["features"]:
                group_lookup[feature] = group_index

        grouped_ranking = []
        handled_groups = set()
        for feature_entry in feature_ranking:
            feature = feature_entry["feature"]
            group_index = group_lookup.get(feature)
            if group_index is None:
                grouped_ranking.append(
                    {
                        "group_type": "single_feature",
                        "features": [feature],
                        "average_rank": feature_entry["average_rank"],
                        "average_selection_frequency": feature_entry["average_selection_frequency"],
                    }
                )
                continue

            if group_index in handled_groups:
                continue
            handled_groups.add(group_index)

            members = correlation_groups[group_index]["features"]
            member_entries = [entry for entry in feature_ranking if entry["feature"] in members]
            grouped_ranking.append(
                {
                    "group_type": "correlated_feature_group",
                    "features": members,
                    "average_rank": float(min(entry["average_rank"] for entry in member_entries)),
                    "average_selection_frequency": float(
                        max(entry["average_selection_frequency"] for entry in member_entries)
                    ),
                    "interpretation": correlation_groups[group_index]["interpretation"],
                }
            )

        grouped_ranking.sort(
            key=lambda item: (
                -item["average_selection_frequency"],
                item["average_rank"],
                item["features"],
            )
        )

        linear_features = by_model.get("linear", {})
        elastic_features = by_model.get("elastic_net", {})
        tree_features = by_model.get("lightgbm", {})
        nonlinear_candidates = []

        for feature in feature_columns:
            if not tree_features or feature not in tree_features:
                continue

            tree_mean_rank = tree_features[feature]["mean_rank"]
            linear_ranks = []
            if feature in linear_features:
                linear_ranks.append(linear_features[feature]["mean_rank"])
            if feature in elastic_features:
                linear_ranks.append(elastic_features[feature]["mean_rank"])
            if not linear_ranks:
                continue

            mean_tree_rank = float(tree_mean_rank)
            mean_linear_rank = float(np.mean(linear_ranks))
            if mean_linear_rank - mean_tree_rank >= 2.0:
                nonlinear_candidates.append(
                    {
                        "feature": feature,
                        "tree_mean_rank": mean_tree_rank,
                        "linear_mean_rank": mean_linear_rank,
                        "interpretation": "High tree importance but lower linear importance suggests nonlinear effects or interactions.",
                    }
                )

        nonlinear_candidates.sort(key=lambda item: (item["tree_mean_rank"], item["linear_mean_rank"], item["feature"]))

        return {
            "feature_ranking": feature_ranking,
            "grouped_ranking": grouped_ranking,
            "robust_features": [
                item["feature"]
                for item in feature_ranking
                if item["robustly_important"]
            ],
            "nonlinear_or_interaction_candidates": nonlinear_candidates,
        }
