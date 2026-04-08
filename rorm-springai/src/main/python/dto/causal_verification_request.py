"""
Request DTOs for the Causal Verification Pipeline.

Maps the PipelineSpec structure into Python dataclasses for
spec-driven causal inference execution.
"""

from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Optional

from .requests import SQLDatasourceConfig


class TreatmentForm(str, Enum):
    CONTINUOUS = "CONTINUOUS"
    BINARY_THRESHOLD = "BINARY_THRESHOLD"
    CATEGORICAL = "CATEGORICAL"


class RefutationType(str, Enum):
    PLACEBO = "PLACEBO"
    RANDOM_CAUSE = "RANDOM_CAUSE"
    SUBSET = "SUBSET"
    TEMPORAL_PLACEBO = "TEMPORAL_PLACEBO"


class FilterOperator(str, Enum):
    IN = "IN"
    GT = "GT"
    LT = "LT"
    EQ = "EQ"


class UnmeasuredMethod(str, Enum):
    E_VALUE = "E_VALUE"
    ROSENBAUM_BOUNDS = "ROSENBAUM_BOUNDS"


class OverlapStrategy(str, Enum):
    TRIM = "TRIM"
    MATCH = "MATCH"
    LATE = "LATE"


class SlicingMethod(str, Enum):
    UNIQUE = "unique"
    QUARTILE = "quartile"


# --- Nested structures ---


@dataclass
class VariantFilter:
    """Composable filter tree for estimation variant data scoping.

    Leaf node (column comparison)::

        {"column": "region", "operator": "IN", "values": ["Northeast_NJ", ...]}

    AND / OR (list of sub-filters)::

        {"AND": [<filter>, <filter>, ...]}
        {"OR":  [<filter>, <filter>, ...]}

    NOT (single sub-filter)::

        {"NOT": <filter>}

    Leaves, AND, OR and NOT can be nested arbitrarily.
    """
    # Leaf fields (set when this node is a comparison)
    column: Optional[str] = None
    operator: Optional[FilterOperator] = None
    values: Optional[list[Any]] = None

    # Composite fields (at most one is set for non-leaf nodes)
    and_filters: Optional[list["VariantFilter"]] = None
    or_filters: Optional[list["VariantFilter"]] = None
    not_filter: Optional["VariantFilter"] = None

    @property
    def is_leaf(self) -> bool:
        return self.column is not None

    @classmethod
    def from_dict(cls, d: dict) -> "VariantFilter":
        if "AND" in d:
            return cls(and_filters=[cls.from_dict(sub) for sub in d["AND"]])
        if "OR" in d:
            return cls(or_filters=[cls.from_dict(sub) for sub in d["OR"]])
        if "NOT" in d:
            return cls(not_filter=cls.from_dict(d["NOT"]))
        return cls(
            column=d["column"],
            operator=FilterOperator(d["operator"]),
            values=d["values"],
        )


@dataclass
class MediatorExclusion:
    column: str
    pathway: str
    direct_effect_variant_id: str

    @classmethod
    def from_dict(cls, d: dict) -> "MediatorExclusion":
        return cls(
            column=d["column"],
            pathway=d["pathway"],
            direct_effect_variant_id=d["direct_effect_variant_id"],
        )


@dataclass
class EstimationVariant:
    id: str
    treatment_column: str
    treatment_form: TreatmentForm
    model_type: str
    w_columns: list[str]
    reference_category: Optional[str] = None
    threshold_value: Optional[float] = None
    notes: Optional[str] = None
    filter: Optional[VariantFilter] = None

    @classmethod
    def from_dict(cls, d: dict) -> "EstimationVariant":
        return cls(
            id=d["id"],
            treatment_column=d["treatment_column"],
            treatment_form=TreatmentForm(d["treatment_form"]),
            model_type=d["model_type"],
            w_columns=d["w_columns"],
            reference_category=d.get("reference_category"),
            threshold_value=d.get("threshold_value"),
            notes=d.get("notes"),
            filter=VariantFilter.from_dict(d["filter"]) if d.get("filter") else None,
        )


@dataclass
class NuisanceR2Gates:
    outcome_abort: float
    outcome_flag: float
    treatment_abort: float
    treatment_flag: float
    treatment_structural_max_r2: float

    @classmethod
    def from_dict(cls, d: dict) -> "NuisanceR2Gates":
        return cls(**d)


@dataclass
class SanityGates:
    expected_direction: int
    abort_magnitude: float
    flag_magnitude: float

    @classmethod
    def from_dict(cls, d: dict) -> "SanityGates":
        return cls(**d)


@dataclass
class PlaceboGates:
    flag_ratio: float

    @classmethod
    def from_dict(cls, d: dict) -> "PlaceboGates":
        return cls(**d)


@dataclass
class QualityGates:
    nuisance_r2: NuisanceR2Gates
    sanity: SanityGates
    placebo: PlaceboGates

    @classmethod
    def from_dict(cls, d: dict) -> "QualityGates":
        return cls(
            nuisance_r2=NuisanceR2Gates.from_dict(d["nuisance_r2"]),
            sanity=SanityGates.from_dict(d["sanity"]),
            placebo=PlaceboGates.from_dict(d["placebo"]),
        )


@dataclass
class MediationConfig:
    mediator: str
    pathway: str
    total_variant_id: str
    direct_variant_id: str

    @classmethod
    def from_dict(cls, d: dict) -> "MediationConfig":
        return cls(**d)


@dataclass
class SlicingConfig:
    column: str
    method: SlicingMethod

    @classmethod
    def from_dict(cls, d: dict) -> "SlicingConfig":
        items = list(d.items())
        return cls(column=items[0][0], method=SlicingMethod(items[0][1]))


@dataclass
class GrfConfig:
    id: str
    modifier_columns: list[str]
    slicing: dict[str, str]

    @classmethod
    def from_dict(cls, d: dict) -> "GrfConfig":
        return cls(
            id=d["id"],
            modifier_columns=d["modifier_columns"],
            slicing=d["slicing"],
        )


@dataclass
class RefutationConfig:
    type: RefutationType

    @classmethod
    def from_dict(cls, d: dict) -> "RefutationConfig":
        return cls(type=RefutationType(d["type"]))


@dataclass
class ConfounderDrop:
    column: str
    deviation_threshold_pct: float

    @classmethod
    def from_dict(cls, d: dict) -> "ConfounderDrop":
        return cls(**d)


@dataclass
class ConfounderAdd:
    column: str
    reasoning: str

    @classmethod
    def from_dict(cls, d: dict) -> "ConfounderAdd":
        return cls(**d)


@dataclass
class ThresholdVariant:
    threshold: float
    expected_n_treated: int
    expected_n_control: int

    @classmethod
    def from_dict(cls, d: dict) -> "ThresholdVariant":
        return cls(**d)


@dataclass
class ModelVariant:
    primary_variant_id: str
    alternative_model_type: str

    @classmethod
    def from_dict(cls, d: dict) -> "ModelVariant":
        return cls(**d)


@dataclass
class SensitivityConfig:
    confounder_drops: list[ConfounderDrop]
    confounder_adds: list[ConfounderAdd]
    threshold_variants: list[ThresholdVariant]
    model_variants: list[ModelVariant]

    @classmethod
    def from_dict(cls, d: dict) -> "SensitivityConfig":
        return cls(
            confounder_drops=[ConfounderDrop.from_dict(x) for x in d.get("confounder_drops", [])],
            confounder_adds=[ConfounderAdd.from_dict(x) for x in d.get("confounder_adds", [])],
            threshold_variants=[ThresholdVariant.from_dict(x) for x in d.get("threshold_variants", [])],
            model_variants=[ModelVariant.from_dict(x) for x in d.get("model_variants", [])],
        )


@dataclass
class StructuralBreakConfig:
    id: str
    entity_column: str
    temporal_column: str
    temporal_grain: str
    pelt_penalty: float
    min_obs_per_period: int
    known_events_tables: Optional[list[str]]
    entity_count: int
    temporal_points: int

    @classmethod
    def from_dict(cls, d: dict) -> "StructuralBreakConfig":
        return cls(
            id=d["id"],
            entity_column=d["entity_column"],
            temporal_column=d["temporal_column"],
            temporal_grain=d["temporal_grain"],
            pelt_penalty=d["pelt_penalty"],
            min_obs_per_period=d["min_obs_per_period"],
            known_events_tables=d.get("known_events_tables"),
            entity_count=d["entity_count"],
            temporal_points=d["temporal_points"],
        )


@dataclass
class AutocorrelationCheck:
    temporal_column: str
    grain: str
    lags: list[int]
    threshold: float

    @classmethod
    def from_dict(cls, d: dict) -> "AutocorrelationCheck":
        return cls(**d)


@dataclass
class FieldCorrelationCheck:
    threshold: float
    check_columns: list[str]

    @classmethod
    def from_dict(cls, d: dict) -> "FieldCorrelationCheck":
        return cls(**d)


@dataclass
class AutoCorrectionConfig:
    max_iterations: int
    stop_criterion_ci_pct: float

    @classmethod
    def from_dict(cls, d: dict) -> "AutoCorrectionConfig":
        return cls(**d)


@dataclass
class MetadataCorrelation:
    column: str
    threshold: float
    alert_type: str

    @classmethod
    def from_dict(cls, d: dict) -> "MetadataCorrelation":
        return cls(**d)


@dataclass
class ResidualChecks:
    autocorrelation: list[AutocorrelationCheck]
    field_correlation: FieldCorrelationCheck
    auto_correction: AutoCorrectionConfig
    metadata_correlation: list[MetadataCorrelation]

    @classmethod
    def from_dict(cls, d: dict) -> "ResidualChecks":
        return cls(
            autocorrelation=[AutocorrelationCheck.from_dict(x) for x in d.get("autocorrelation", [])],
            field_correlation=FieldCorrelationCheck.from_dict(d["field_correlation"]),
            auto_correction=AutoCorrectionConfig.from_dict(d["auto_correction"]),
            metadata_correlation=[MetadataCorrelation.from_dict(x) for x in d.get("metadata_correlation", [])],
        )


@dataclass
class VifConfig:
    threshold: float
    drop_pairs: list[dict[str, str]]

    @classmethod
    def from_dict(cls, d: dict) -> "VifConfig":
        return cls(threshold=d["threshold"], drop_pairs=d.get("drop_pairs", []))


@dataclass
class OverlapCheck:
    variant_id: str
    threshold: float
    response_strategy: OverlapStrategy
    trim_bounds: Optional[list[float]] = None

    @classmethod
    def from_dict(cls, d: dict) -> "OverlapCheck":
        return cls(
            variant_id=d["variant_id"],
            threshold=d["threshold"],
            response_strategy=OverlapStrategy(d["response_strategy"]),
            trim_bounds=d.get("trim_bounds"),
        )


@dataclass
class VarianceCheck:
    column: str
    structural_note: str

    @classmethod
    def from_dict(cls, d: dict) -> "VarianceCheck":
        return cls(**d)


@dataclass
class RangeChecks:
    vif: VifConfig
    overlap: list[OverlapCheck]
    variance: list[VarianceCheck]

    @classmethod
    def from_dict(cls, d: dict) -> "RangeChecks":
        return cls(
            vif=VifConfig.from_dict(d["vif"]),
            overlap=[OverlapCheck.from_dict(x) for x in d.get("overlap", [])],
            variance=[VarianceCheck.from_dict(x) for x in d.get("variance", [])],
        )


@dataclass
class UnmeasuredConfoundingConfig:
    variant_id: str
    method: UnmeasuredMethod
    null_hypothesis: str
    notes: str

    @classmethod
    def from_dict(cls, d: dict) -> "UnmeasuredConfoundingConfig":
        return cls(
            variant_id=d["variant_id"],
            method=UnmeasuredMethod(d["method"]),
            null_hypothesis=d["null_hypothesis"],
            notes=d["notes"],
        )


@dataclass
class DomainRanking:
    """Domain-predicted severity ordering using tier notation.

    ``ordering`` uses parenthesized tiers separated by ``>``:

        (70) > (60, 80) > (50, 90)

    Elements within a tier are asserted as approximately equal (~).
    Cross-tier pairs assert left > right in effect magnitude.
    The notation unwinds to all valid chains and checks each pair.
    """
    ordering: str
    source: str
    scope: str
    expected_concordance: float

    @classmethod
    def from_dict(cls, d: dict) -> "DomainRanking":
        return cls(
            ordering=d.get("ordering", ""),
            source=d.get("source", ""),
            scope=d.get("scope", ""),
            expected_concordance=d.get("expected_concordance", 0.0),
        )


@dataclass
class AllocationBias:
    treatment_column: str
    grouping_column: str
    flag_threshold: float

    @classmethod
    def from_dict(cls, d: dict) -> "AllocationBias":
        return cls(**d)


@dataclass
class ExternalizationConfig:
    domain_rankings: list[DomainRanking]
    allocation_bias: list[AllocationBias]

    @classmethod
    def from_dict(cls, d: dict) -> "ExternalizationConfig":
        return cls(
            domain_rankings=[DomainRanking.from_dict(x) for x in d.get("domain_rankings", [])],
            allocation_bias=[AllocationBias.from_dict(x) for x in d.get("allocation_bias", [])],
        )


@dataclass
class PositivityCheck:
    confounder_column: str
    treatment_hierarchy: list[str]
    min_cell_threshold: int = 50
    relative_threshold: float = 0.3

    @classmethod
    def from_dict(cls, d: dict) -> "PositivityCheck":
        return cls(
            confounder_column=d["confounder_column"],
            treatment_hierarchy=d["treatment_hierarchy"],
            min_cell_threshold=d.get("min_cell_threshold", 50),
            relative_threshold=d.get("relative_threshold", 0.3),
        )


@dataclass
class DiscrepancyEntry:
    field_name: str
    generator_value: str
    compiler_value: str
    resolution: str

    @classmethod
    def from_dict(cls, d: dict) -> "DiscrepancyEntry":
        return cls(
            field_name=d["field"],
            generator_value=d["generator_value"],
            compiler_value=d["compiler_value"],
            resolution=d["resolution"],
        )


# --- Top-level spec ---


@dataclass
class CausalVerificationRequest:
    """
    Full PipelineSpec for running the causal verification pipeline.

    Drives all 13 steps: d-sep refinement, identification, estimation,
    quality gates, mediation, GRF heterogeneity, refutations, sensitivity,
    structural breaks, residual diagnostics, range checks, unmeasured
    confounding, and externalization.
    """

    hypothesis_id: str
    treatment: str
    outcome: str
    treatment_form: TreatmentForm

    datasource: SQLDatasourceConfig
    expected_row_count: int
    strip_columns: list[str]

    dag_edges: str
    dsep_threshold: float

    adjustment_set: list[str]
    mediators_excluded: list[MediatorExclusion]

    positivity_check: Optional[PositivityCheck]

    estimation_variants: list[EstimationVariant]

    gates: QualityGates

    mediation: Optional[list[MediationConfig]]

    grf_configs: list[GrfConfig]

    refutations: list[RefutationConfig]

    sensitivity: SensitivityConfig

    structural_breaks: list[StructuralBreakConfig]

    residual_checks: ResidualChecks

    range_checks: RangeChecks

    unmeasured_confounding: list[UnmeasuredConfoundingConfig]

    externalization: ExternalizationConfig

    discrepancy_log: list[DiscrepancyEntry] = field(default_factory=list)

    @classmethod
    def from_dict(cls, d: dict[str, Any]) -> "CausalVerificationRequest":
        return cls(
            hypothesis_id=d["hypothesis_id"],
            treatment=d["treatment"],
            outcome=d["outcome"],
            treatment_form=TreatmentForm(d["treatment_form"]),
            datasource=SQLDatasourceConfig.from_dict(d["datasource"]),
            expected_row_count=d["expected_row_count"],
            strip_columns=d.get("strip_columns") or [],
            dag_edges=d["dag_edges"],
            dsep_threshold=d["dsep_threshold"],
            adjustment_set=d["adjustment_set"],
            mediators_excluded=[MediatorExclusion.from_dict(x) for x in (d.get("mediators_excluded") or [])],
            positivity_check=PositivityCheck.from_dict(d["positivity_check"]) if d.get("positivity_check") else None,
            estimation_variants=[EstimationVariant.from_dict(x) for x in d["estimation_variants"]],
            gates=QualityGates.from_dict(d["gates"]),
            mediation=[MediationConfig.from_dict(x) for x in d["mediation"]] if d.get("mediation") else None,
            grf_configs=[GrfConfig.from_dict(x) for x in (d.get("grf_configs") or [])],
            refutations=[RefutationConfig.from_dict(x) for x in (d.get("refutations") or [])],
            sensitivity=SensitivityConfig.from_dict(d["sensitivity"]),
            structural_breaks=[StructuralBreakConfig.from_dict(x) for x in (d.get("structural_breaks") or [])],
            residual_checks=ResidualChecks.from_dict(d["residual_checks"]),
            range_checks=RangeChecks.from_dict(d["range_checks"]),
            unmeasured_confounding=[
                UnmeasuredConfoundingConfig.from_dict(x) for x in (d.get("unmeasured_confounding") or [])
            ],
            externalization=ExternalizationConfig.from_dict(d.get("externalization") or {}),
            discrepancy_log=[DiscrepancyEntry.from_dict(x) for x in (d.get("discrepancy_log") or [])],
        )

    def to_dict(self) -> dict[str, Any]:
        from dataclasses import asdict
        return asdict(self)
