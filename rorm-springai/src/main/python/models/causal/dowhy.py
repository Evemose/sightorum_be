"""DoWhy causal inference model trainer."""

import polars as pl
from core.exceptions import ModelTrainingError
from typing import Any, Optional

from .common import CausalAnalysisResult
from ..base import ModelCategory, ModelTrainer


class DoWhyTrainer(ModelTrainer):
    """Train a causal effect estimate using DoWhy."""

    @property
    def model_type(self) -> str:
        return "dowhy"

    @property
    def category(self) -> ModelCategory:
        return ModelCategory.CAUSAL

    @property
    def requires_target(self) -> bool:
        return True

    @property
    def minimum_rows(self) -> int:
        return 30

    @property
    def default_params(self) -> dict[str, Any]:
        return {
            "treatment_column": None,
            "common_causes": None,
            "instruments": None,
            "effect_modifiers": None,
            "estimand_type": "nonparametric-ate",
            "method_name": "backdoor.linear_regression",
            "control_value": 0,
            "treatment_value": 1,
            "proceed_when_unidentifiable": False,
            "test_significance": True,
            "evaluate_effect_strength": False,
        }

    @property
    def param_schema(self) -> dict[str, dict[str, Any]]:
        return {
            "treatment_column": {
                "type": "str",
                "required": True,
                "description": "Column in feature_columns that represents the treatment variable.",
            },
            "common_causes": {
                "type": "list",
                "required": False,
                "description": "Optional list of confounder column names.",
            },
            "instruments": {
                "type": "list",
                "required": False,
                "description": "Optional list of instrumental variable column names.",
            },
            "effect_modifiers": {
                "type": "list",
                "required": False,
                "description": "Optional list of effect modifier column names.",
            },
            "estimand_type": {
                "type": "str",
                "required": False,
                "default": "nonparametric-ate",
                "description": "DoWhy estimand type, typically nonparametric-ate.",
            },
            "method_name": {
                "type": "str",
                "required": False,
                "default": "backdoor.linear_regression",
                "description": "DoWhy estimation method identifier.",
            },
            "control_value": {
                "type": "float",
                "required": False,
                "default": 0,
                "description": "Treatment control value for effect estimation.",
            },
            "treatment_value": {
                "type": "float",
                "required": False,
                "default": 1,
                "description": "Treatment value for effect estimation.",
            },
            "proceed_when_unidentifiable": {
                "type": "bool",
                "required": False,
                "default": False,
                "description": "Allow estimation when identification assumptions are incomplete.",
            },
            "test_significance": {
                "type": "bool",
                "required": False,
                "default": True,
                "description": "Request significance testing from the estimator when supported.",
            },
            "evaluate_effect_strength": {
                "type": "bool",
                "required": False,
                "default": False,
                "description": "Request effect strength evaluation when supported.",
            },
        }

    def validate_params(self, params: dict[str, Any]):
        result = super().validate_params(params)
        for field_name in ("common_causes", "instruments", "effect_modifiers"):
            values = params.get(field_name)
            if values is None:
                continue
            if any(not isinstance(value, str) or not value.strip() for value in values):
                result.add_error(
                    f"model_params.{field_name}",
                    f"Parameter '{field_name}' must contain only non-empty strings",
                )
        return result

    def train(
            self,
            X: pl.DataFrame,
            y: Optional[pl.Series],
            params: dict[str, Any],
    ) -> tuple[Any, dict[str, Any]]:
        """Train a DoWhy causal model."""
        try:
            from dowhy import CausalModel
        except ImportError as exc:
            raise ModelTrainingError.training_failed(
                self.model_type,
                "DoWhy is not installed. Install it with: pip install dowhy",
                type(exc).__name__,
            )

        if y is None or not y.name:
            raise ModelTrainingError(
                model_type=self.model_type,
                message="DoWhy requires a named target column to use as the outcome variable",
            )

        treatment_column = params["treatment_column"]
        if treatment_column not in X.columns:
            raise ModelTrainingError.invalid_parameters(
                self.model_type,
                "treatment_column",
                f"Column '{treatment_column}' must be included in feature_columns",
            )

        outcome_column = y.name
        common_causes = params.get("common_causes")
        if common_causes is None:
            common_causes = [column for column in X.columns if column != treatment_column]

        instruments = params.get("instruments") or None
        effect_modifiers = params.get("effect_modifiers") or None

        analysis_df = X.with_columns(y).to_pandas()
        model = CausalModel(
            data=analysis_df,
            treatment=treatment_column,
            outcome=outcome_column,
            common_causes=common_causes or None,
            instruments=instruments,
            effect_modifiers=effect_modifiers,
            estimand_type=params["estimand_type"],
        )

        identified_estimand = model.identify_effect(
            proceed_when_unidentifiable=params["proceed_when_unidentifiable"]
        )
        estimate = model.estimate_effect(
            identified_estimand,
            method_name=params["method_name"],
            control_value=params["control_value"],
            treatment_value=params["treatment_value"],
            test_significance=params["test_significance"],
            evaluate_effect_strength=params["evaluate_effect_strength"],
        )

        estimated_effect = float(estimate.value)
        p_value = self._extract_p_value(estimate)

        details = {
            "estimand_type": params["estimand_type"],
            "method_name": params["method_name"],
            "control_value": params["control_value"],
            "treatment_value": params["treatment_value"],
            "identified_estimand": str(identified_estimand),
            "estimate": str(estimate),
        }
        if common_causes:
            details["common_causes"] = list(common_causes)
        if instruments:
            details["instruments"] = list(instruments)
        if effect_modifiers:
            details["effect_modifiers"] = list(effect_modifiers)
        if p_value is not None:
            details["p_value"] = p_value

        result_model = CausalAnalysisResult(
            backend="dowhy",
            outcome_column=outcome_column,
            treatment_column=treatment_column,
            summary=str(estimate),
            details=details,
            results_data=[
                             {
                                 "metric": "estimated_effect",
                                 "value": estimated_effect,
                             },
                             {
                                 "metric": "absolute_effect",
                                 "value": abs(estimated_effect),
                             },
                         ] + ([{
                "metric": "p_value",
                "value": p_value,
            }] if p_value is not None else []),
        )

        metrics: dict[str, Any] = {
            "estimated_effect": estimated_effect,
            "absolute_effect": abs(estimated_effect),
            "treatment_column": treatment_column,
            "outcome_column": outcome_column,
            "method_name": params["method_name"],
            "n_common_causes": len(common_causes),
            "has_instruments": bool(instruments),
            "has_effect_modifiers": bool(effect_modifiers),
        }
        if p_value is not None:
            metrics["p_value"] = p_value

        return result_model, metrics

    @staticmethod
    def _extract_p_value(estimate: Any) -> Optional[float]:
        """Best-effort extraction across DoWhy estimator variants."""
        for attr_name in ("p_value", "pvalue"):
            value = getattr(estimate, attr_name, None)
            if value is None:
                continue
            if isinstance(value, dict):
                if "p_value" in value:
                    return float(value["p_value"])
                if "pvalue" in value:
                    return float(value["pvalue"])
            if isinstance(value, (int, float)):
                return float(value)

        significance = getattr(estimate, "test_stat_significance", None)
        if isinstance(significance, dict):
            if "p_value" in significance:
                return float(significance["p_value"])
            if "pvalue" in significance:
                return float(significance["pvalue"])

        return None
