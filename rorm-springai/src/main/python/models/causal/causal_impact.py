"""CausalImpact model trainer."""

import polars as pl
from core.exceptions import ModelTrainingError
from typing import Any, Optional

from .common import CausalAnalysisResult
from ..base import ModelCategory, ModelTrainer


class CausalImpactTrainer(ModelTrainer):
    """Estimate intervention impact on a time series using CausalImpact."""

    @property
    def model_type(self) -> str:
        return "causal_impact"

    @property
    def category(self) -> ModelCategory:
        return ModelCategory.CAUSAL

    @property
    def requires_target(self) -> bool:
        return True

    @property
    def minimum_rows(self) -> int:
        return 20

    @property
    def default_params(self) -> dict[str, Any]:
        return {
            "pre_period_end": None,
            "post_period_start": None,
            "post_period_end": None,
            "alpha": 0.05,
        }

    @property
    def param_schema(self) -> dict[str, dict[str, Any]]:
        return {
            "pre_period_end": {
                "type": "int",
                "required": True,
                "min": 1,
                "description": "Zero-based inclusive row index where the pre-intervention period ends.",
            },
            "post_period_start": {
                "type": "int",
                "required": False,
                "min": 1,
                "description": "Zero-based inclusive row index where the post-intervention period starts.",
            },
            "post_period_end": {
                "type": "int",
                "required": False,
                "min": 1,
                "description": "Zero-based inclusive row index where the post-intervention period ends.",
            },
            "alpha": {
                "type": "float",
                "required": False,
                "default": 0.05,
                "min": 0.001,
                "max": 0.2,
                "description": "Posterior tail-area probability threshold.",
            },
        }

    def train(
            self,
            X: pl.DataFrame,
            y: Optional[pl.Series],
            params: dict[str, Any],
    ) -> tuple[Any, dict[str, Any]]:
        """Train a CausalImpact model."""
        try:
            from causalimpact import CausalImpact
        except ImportError as exc:
            raise ModelTrainingError.training_failed(
                self.model_type,
                "CausalImpact is not installed. Install it with: pip install causalimpact",
                type(exc).__name__,
            )

        if y is None or not y.name:
            raise ModelTrainingError(
                model_type=self.model_type,
                message="CausalImpact requires a named target column as the response series",
            )

        series_df = X.with_columns(y).to_pandas()
        ordered_columns = [y.name] + [column for column in X.columns]
        series_df = series_df[ordered_columns]

        row_count = len(series_df)
        pre_period_end = params["pre_period_end"]
        post_period_start = params["post_period_start"]
        post_period_end = params["post_period_end"]

        if post_period_start is None:
            post_period_start = pre_period_end + 1
        if post_period_end is None:
            post_period_end = row_count - 1

        if pre_period_end >= row_count - 1:
            raise ModelTrainingError.invalid_parameters(
                self.model_type,
                "pre_period_end",
                "pre_period_end must leave at least one row for the post-intervention period",
            )
        if post_period_start <= pre_period_end:
            raise ModelTrainingError.invalid_parameters(
                self.model_type,
                "post_period_start",
                "post_period_start must be greater than pre_period_end",
            )
        if post_period_end < post_period_start or post_period_end >= row_count:
            raise ModelTrainingError.invalid_parameters(
                self.model_type,
                "post_period_end",
                f"post_period_end must be between {post_period_start} and {row_count - 1}",
            )

        impact = CausalImpact(
            series_df,
            [0, pre_period_end],
            [post_period_start, post_period_end],
            alpha=params["alpha"],
        )

        inferences = getattr(impact, "inferences", None)
        if inferences is None or getattr(inferences, "empty", False):
            results_data: list[dict[str, Any]] = []
            average_effect = 0.0
            cumulative_effect = 0.0
            relative_effect = None
        else:
            results_data = self._normalize_inferences(inferences)
            average_effect = self._column_stat(inferences, ("point_effect", "point_effects"), "mean")
            cumulative_effect = self._column_stat(inferences, ("cum_effect", "cum_effects"), "last")
            relative_effect = self._column_stat(
                inferences,
                ("relative_effect", "rel_effect", "relative_effects"),
                "last",
            )

        if average_effect is None:
            average_effect = 0.0
        if cumulative_effect is None:
            cumulative_effect = 0.0

        summary = self._get_summary(impact)
        report = self._get_report(impact)

        result_model = CausalAnalysisResult(
            backend="causalimpact",
            outcome_column=y.name,
            summary=summary,
            details={
                "pre_period": [0, pre_period_end],
                "post_period": [post_period_start, post_period_end],
                "alpha": params["alpha"],
                "report": report,
            },
            results_data=results_data,
        )

        metrics: dict[str, Any] = {
            "average_effect": average_effect,
            "absolute_effect": abs(average_effect),
            "cumulative_effect": cumulative_effect,
            "pre_period": [0, pre_period_end],
            "post_period": [post_period_start, post_period_end],
            "alpha": params["alpha"],
            "n_covariates": X.width,
        }
        if relative_effect is not None:
            metrics["relative_effect"] = relative_effect

        return result_model, metrics

    @staticmethod
    def _normalize_inferences(inferences) -> list[dict[str, Any]]:
        records = inferences.reset_index().to_dict(orient="records")
        normalized: list[dict[str, Any]] = []
        for record in records:
            normalized_record = {}
            for key, value in record.items():
                if hasattr(value, "item"):
                    try:
                        normalized_record[key] = value.item()
                        continue
                    except Exception:
                        pass
                normalized_record[key] = value
            normalized.append(normalized_record)
        return normalized

    @staticmethod
    def _column_stat(inferences, candidates: tuple[str, ...], mode: str) -> Optional[float]:
        for column_name in candidates:
            if column_name not in inferences.columns:
                continue
            series = inferences[column_name].dropna()
            if series.empty:
                return None
            if mode == "mean":
                return float(series.mean())
            if mode == "last":
                return float(series.iloc[-1])
        return None

    @staticmethod
    def _get_summary(impact: Any) -> str:
        summary_fn = getattr(impact, "summary", None)
        if callable(summary_fn):
            return str(summary_fn())
        return "CausalImpact analysis completed"

    @staticmethod
    def _get_report(impact: Any) -> str:
        summary_fn = getattr(impact, "summary", None)
        if callable(summary_fn):
            try:
                return str(summary_fn(output="report"))
            except TypeError:
                return ""
        return ""
