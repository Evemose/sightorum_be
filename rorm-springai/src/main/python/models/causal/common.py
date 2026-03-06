"""Shared artifacts for causal model trainers."""

from dataclasses import dataclass, field
from typing import Any, Optional


@dataclass
class CausalAnalysisResult:
    """Serializable causal analysis output stored as a trained model artifact."""

    backend: str
    outcome_column: str
    treatment_column: Optional[str] = None
    summary: str = ""
    details: dict[str, Any] = field(default_factory=dict)
    results_data: list[dict[str, Any]] = field(default_factory=list)
