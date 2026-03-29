"""Thin DataFrame wrapper that holds raw + label-encoded views side by side.

Created once per pipeline run in ``_load_data``.  Every downstream step
uses ``df.encoded`` for model fitting and ``df.raw`` (or ``df[col]``)
for filters, labels, and slicing.

Encoding is **stable across runs**: categories are sorted alphabetically
before assigning integer codes, so the same value always maps to the
same code regardless of row order in the source data.

Memory: numeric columns are shared (not copied) between raw and encoded
views.  Only categorical columns are duplicated with different values.
"""

from __future__ import annotations

import numpy as np
import pandas as pd
from typing import Any, Sequence


class PipelineDataFrame:
    """Dual-view DataFrame: raw strings + deterministic integer encoding."""

    __slots__ = ("_raw", "_encoded", "_cat_columns", "_encoders")

    def __init__(
            self,
            raw: pd.DataFrame,
            encoded: pd.DataFrame,
            cat_columns: list[str],
            encoders: dict[str, dict[Any, int]],
    ):
        self._raw = raw
        self._encoded = encoded
        self._cat_columns = cat_columns
        self._encoders = encoders

    @classmethod
    def from_dataframe(cls, df: pd.DataFrame) -> PipelineDataFrame:
        """Build from a plain DataFrame, encoding all string/object/category columns.

        Numeric columns share memory between raw and encoded views —
        only categorical columns are duplicated.
        """
        cat_cols = df.select_dtypes(include=["object", "category", "string"]).columns.tolist()
        if not cat_cols:
            return cls(df, df, [], {})

        # Build encoded view sharing numeric columns (no full copy)
        encoded_cols: dict[str, pd.Series] = {}
        encoders: dict[str, dict[Any, int]] = {}
        for col in df.columns:
            if col in cat_cols:
                categories = sorted(df[col].dropna().unique())
                mapping = {v: i for i, v in enumerate(categories)}
                encoders[col] = mapping
                encoded_cols[col] = df[col].map(mapping).astype("Int64")
            else:
                encoded_cols[col] = df[col]  # shared reference, no copy

        encoded = pd.DataFrame(encoded_cols, index=df.index)
        return cls(df, encoded, cat_cols, encoders)

    # -- primary accessors ------------------------------------------------

    @property
    def raw(self) -> pd.DataFrame:
        """Original data with string columns intact (for filters, labels)."""
        return self._raw

    @property
    def encoded(self) -> pd.DataFrame:
        """Label-encoded data (for DML, GRF, cross_val_score, pearsonr)."""
        return self._encoded

    @property
    def columns(self) -> pd.Index:
        return self._raw.columns

    @property
    def index(self) -> pd.Index:
        return self._raw.index

    @property
    def cat_columns(self) -> list[str]:
        """Column names that were label-encoded."""
        return self._cat_columns

    @property
    def encoders(self) -> dict[str, dict[Any, int]]:
        """``{col: {original_value: int_code}}`` mappings (stable/sorted)."""
        return self._encoders

    # -- DataFrame-like access (delegates to raw) -------------------------

    def __len__(self) -> int:
        return len(self._raw)

    def __getitem__(self, key) -> Any:
        """Index into raw data (for filters, isin, comparisons)."""
        return self._raw[key]

    def __contains__(self, key) -> bool:
        return key in self._raw.columns

    # -- filtering --------------------------------------------------------

    def filter_mask(self, mask: pd.Series) -> PipelineDataFrame:
        """Apply a boolean mask, returning a new wrapper with both views filtered."""
        idx = mask.values.nonzero()[0]
        return PipelineDataFrame(
            self._raw.iloc[idx].reset_index(drop=True),
            self._encoded.iloc[idx].reset_index(drop=True),
            self._cat_columns,
            self._encoders,
        )

    # -- convenience for model fitting ------------------------------------

    def encoded_values(self, cols: list[str]) -> np.ndarray:
        """Return encoded numpy array for the given columns."""
        return self._encoded[cols].values

    def encoded_series(self, col: str) -> pd.Series:
        """Return a single encoded column as a Series."""
        return self._encoded[col]

    # -- delegation for common pandas operations --------------------------

    def memory_usage(self, **kwargs) -> pd.Series:
        return self._raw.memory_usage(**kwargs)

    def sort_values(self, by, **kwargs) -> pd.DataFrame:
        """Sort raw data (returns plain DataFrame, not wrapped)."""
        return self._raw.sort_values(by, **kwargs)

    def groupby(self, *args, **kwargs):
        """Group raw data."""
        return self._raw.groupby(*args, **kwargs)
