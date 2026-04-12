"""
Descriptive statistics service — pure-function stats called by the Java
descriptive analytics tools.

Covers the spec checks that need real stats libraries (scipy, statsmodels,
ruptures): Hartigan-style multimodality, STL decomposition with trend and
seasonality strength, autocorrelation peak detection, PELT change points.
"""

from __future__ import annotations

import logging
import numpy as np
from dto.descriptive_stats_dto import (
    AutocorrPeak,
    DistributionShapeResponse,
    SeriesAnalysisResponse,
    StlResult,
)
from scipy import stats
from scipy.stats import gaussian_kde
from typing import List, Optional

logger = logging.getLogger(__name__)


def distribution_shape(
        values: List[float],
        run_dip_test: bool = True,
) -> DistributionShapeResponse:
    """Moments + multimodality on a numeric sample."""
    arr = np.asarray([v for v in values if v is not None and np.isfinite(v)], dtype=float)
    n = int(arr.size)
    notes: List[str] = []

    if n < 4:
        return DistributionShapeResponse(
            n=n,
            skew=0.0,
            excess_kurtosis=0.0,
            dip_p=None,
            is_multimodal=False,
            mode_count=0,
            mode_separation_ratio=0.0,
            notes=["insufficient data (n < 4) — shape checks skipped"],
        )

    std = float(np.std(arr))
    if std == 0.0:
        return DistributionShapeResponse(
            n=n,
            skew=0.0,
            excess_kurtosis=0.0,
            dip_p=None,
            is_multimodal=False,
            mode_count=1,
            mode_separation_ratio=0.0,
            notes=["zero variance — all values identical"],
        )

    skew = float(stats.skew(arr, bias=False))
    kurt = float(stats.kurtosis(arr, fisher=True, bias=False))

    dip_p: Optional[float] = None
    mode_count = 1
    mode_separation_ratio = 0.0
    is_multimodal = False

    if run_dip_test:
        try:
            mode_count, mode_separation_ratio = _kde_mode_analysis(arr)
            if mode_count >= 2 and mode_separation_ratio > 0.5:
                is_multimodal = True
            dip_p = _approx_unimodality_p(skew, kurt, mode_count, mode_separation_ratio)
        except Exception as exc:
            notes.append(f"multimodality estimate failed: {exc}")

    return DistributionShapeResponse(
        n=n,
        skew=skew,
        excess_kurtosis=kurt,
        dip_p=dip_p,
        is_multimodal=is_multimodal,
        mode_count=mode_count,
        mode_separation_ratio=mode_separation_ratio,
        notes=notes,
    )


def _kde_mode_analysis(arr: np.ndarray) -> tuple[int, float]:
    """
    Count local maxima of a Gaussian KDE over a fine grid and return their
    separation relative to the IQR of the data.
    """
    q1, q3 = np.percentile(arr, [25, 75])
    iqr = float(q3 - q1)
    if iqr == 0.0:
        return 1, 0.0

    kde = gaussian_kde(arr, bw_method="scott")
    lo = float(np.min(arr))
    hi = float(np.max(arr))
    pad = (hi - lo) * 0.05 if hi > lo else 1.0
    grid = np.linspace(lo - pad, hi + pad, 512)
    density = kde(grid)

    mode_positions: List[float] = []
    for i in range(1, len(density) - 1):
        if density[i] > density[i - 1] and density[i] > density[i + 1]:
            if density[i] > 0.05 * float(np.max(density)):
                mode_positions.append(float(grid[i]))

    mode_count = max(1, len(mode_positions))
    if mode_count < 2:
        return mode_count, 0.0

    separations = [
        abs(b - a) for a, b in zip(mode_positions[:-1], mode_positions[1:])
    ]
    max_separation = max(separations)
    return mode_count, float(max_separation / iqr)


def _approx_unimodality_p(
        skew: float, kurt: float, mode_count: int, mode_separation_ratio: float
) -> float:
    """
    Rough unimodality p-value without the full Hartigan dip algorithm.

    Combines skew/kurt deviation from normal with mode-count evidence.
    Low values indicate 'reject unimodal'. This is a calibration-free
    heuristic, not a formal test.
    """
    score = 0.0
    score += min(abs(skew) / 2.0, 1.0) * 0.3
    score += min(abs(kurt) / 3.0, 1.0) * 0.2
    if mode_count >= 2:
        score += 0.3
    score += min(mode_separation_ratio / 2.0, 1.0) * 0.2
    return float(max(0.0, 1.0 - score))


def series_analysis(
        values: List[float],
        period: Optional[int] = None,
        run_stl: bool = False,
        run_autocorr: bool = False,
        run_changepoint: bool = False,
        pelt_penalty: float = 5.0,
) -> SeriesAnalysisResponse:
    """Time-series checks — STL decomposition, ACF peak, PELT change points."""
    arr = np.asarray([v if v is not None else np.nan for v in values], dtype=float)
    n = int(arr.size)
    notes: List[str] = []

    if n < 4:
        return SeriesAnalysisResponse(
            n=n,
            notes=["series too short (n < 4)"],
        )

    if np.isnan(arr).any():
        mask = ~np.isnan(arr)
        if mask.sum() < 4:
            return SeriesAnalysisResponse(n=n, notes=["too few non-NaN values"])
        arr = _interp_nan(arr, mask)
        notes.append("NaN values linearly interpolated")

    stl_result = None
    autocorr_peak = None
    changepoints: Optional[List[int]] = None

    if run_stl:
        stl_result = _compute_stl(arr, period, notes)

    if run_autocorr:
        autocorr_peak = _compute_autocorr_peak(arr, notes)

    if run_changepoint:
        changepoints = _compute_changepoints(arr, pelt_penalty, notes)

    return SeriesAnalysisResponse(
        n=n,
        stl=stl_result,
        autocorr_peak=autocorr_peak,
        changepoints=changepoints,
        notes=notes,
    )


def _interp_nan(arr: np.ndarray, mask: np.ndarray) -> np.ndarray:
    idx = np.arange(arr.size)
    out = arr.copy()
    out[~mask] = np.interp(idx[~mask], idx[mask], arr[mask])
    return out


def _compute_stl(arr, period, notes):
    try:
        from statsmodels.tsa.seasonal import STL
    except Exception as exc:
        notes.append(f"statsmodels STL unavailable: {exc}")
        return None
    if period is None or period < 2:
        notes.append("STL skipped — no valid period hint")
        return None
    if arr.size < 2 * period:
        notes.append(f"STL skipped — series length {arr.size} < 2 * period {period}")
        return None
    try:
        # Seasonal smoother window: odd, >= 7, independent of period
        seasonal_window = 7
        # Trend smoother: Cleveland's rule, next odd >= 1.5*period/(1 - 1.5/seasonal)
        trend_window = int(np.ceil(1.5 * period / (1 - 1.5 / seasonal_window)))
        if trend_window % 2 == 0:
            trend_window += 1
        trend_window = max(trend_window, seasonal_window + 2)

        result = STL(
            arr,
            period=period,  # pass through, do NOT mutate
            seasonal=seasonal_window,
            trend=trend_window,
            robust=False,
        ).fit()
        trend = np.asarray(result.trend)
        seasonal = np.asarray(result.seasonal)
        remainder = np.asarray(result.resid)

        var_r = float(np.var(remainder))
        var_tr = float(np.var(trend + remainder))
        var_sr = float(np.var(seasonal + remainder))
        f_t = max(0.0, 1.0 - var_r / var_tr) if var_tr > 0 else 0.0
        f_s = max(0.0, 1.0 - var_r / var_sr) if var_sr > 0 else 0.0

        return StlResult(
            f_t=f_t,
            f_s=f_s,
            trend=trend.tolist(),
            seasonal=seasonal.tolist(),
            remainder=remainder.tolist(),
        )
    except Exception as exc:
        notes.append(f"STL fit failed: {exc}")
        return None


def _compute_autocorr_peak(
        arr: np.ndarray, notes: List[str]
) -> Optional[AutocorrPeak]:
    try:
        from statsmodels.tsa.stattools import acf
    except Exception as exc:
        notes.append(f"statsmodels acf unavailable: {exc}")
        return None

    max_lag = min(arr.size - 1, max(2, arr.size // 2))
    if max_lag < 2:
        notes.append("autocorr skipped — series too short for lag > 1")
        return None

    try:
        vals = acf(arr, nlags=max_lag, fft=True)
        if len(vals) < 2:
            return None
        candidates = vals[2:]
        if candidates.size == 0:
            return None
        best_offset = int(np.argmax(candidates))
        peak_lag = best_offset + 2
        peak_value = float(candidates[best_offset])
        return AutocorrPeak(peak_lag=peak_lag, peak_value=peak_value)
    except Exception as exc:
        notes.append(f"autocorr failed: {exc}")
        return None


def _compute_changepoints(
        arr: np.ndarray, penalty: float, notes: List[str]
) -> Optional[List[int]]:
    try:
        import ruptures as rpt
    except Exception as exc:
        notes.append(f"ruptures unavailable: {exc}")
        return None

    try:
        algo = rpt.Pelt(model="l2").fit(arr)
        breakpoints = algo.predict(pen=penalty)
        return [int(b) for b in breakpoints if 0 < b < arr.size]
    except Exception as exc:
        notes.append(f"PELT failed: {exc}")
        return None
