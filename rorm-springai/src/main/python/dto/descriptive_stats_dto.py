"""
Pydantic request/response models for the descriptive statistics endpoints.

These endpoints back the Java descriptive analytics tools (SummaryStat, Ranking,
Trend, Comparison) with heavy stats that need scipy/statsmodels/ruptures.
"""

from pydantic import BaseModel, Field
from typing import List, Optional


class DistributionShapeRequest(BaseModel):
    values: List[float] = Field(..., description="Numeric values to analyze")
    run_dip_test: bool = Field(True, description="Run Hartigan-style dip test / KDE multimodality")


class DistributionShapeResponse(BaseModel):
    n: int
    skew: float
    excess_kurtosis: float
    dip_p: Optional[float] = Field(
        None, description="Approximate p-value for unimodality; low = reject unimodal"
    )
    is_multimodal: bool
    mode_count: int
    mode_separation_ratio: float = Field(
        0.0, description="Largest mode separation relative to IQR (0.0 if <2 modes)"
    )
    notes: List[str] = Field(default_factory=list)


class SeriesAnalysisRequest(BaseModel):
    values: List[float]
    period: Optional[int] = Field(
        None, description="Seasonal period in buckets; required for STL"
    )
    run_stl: bool = False
    run_autocorr: bool = False
    run_changepoint: bool = False
    pelt_penalty: float = Field(5.0, description="PELT penalty (higher = fewer breaks)")


class StlResult(BaseModel):
    f_t: float = Field(..., description="Strength of trend in [0, 1]")
    f_s: float = Field(..., description="Strength of seasonality in [0, 1]")
    trend: List[float]
    seasonal: List[float]
    remainder: List[float]


class AutocorrPeak(BaseModel):
    peak_lag: int
    peak_value: float


class SeriesAnalysisResponse(BaseModel):
    n: int
    stl: Optional[StlResult] = None
    autocorr_peak: Optional[AutocorrPeak] = None
    changepoints: Optional[List[int]] = None
    notes: List[str] = Field(default_factory=list)
