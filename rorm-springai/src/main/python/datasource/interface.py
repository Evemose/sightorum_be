"""
Abstract interface for data sources.

This interface allows for different data source implementations
(SQL, CSV, Parquet, API, etc.) while providing a consistent
API that returns Polars DataFrames.
"""

import polars as pl
from abc import ABC, abstractmethod
from dataclasses import dataclass
from typing import Any, Optional


@dataclass
class DataSourceResult:
    """Result of a data source query."""

    dataframe: pl.DataFrame
    row_count: int
    column_count: int
    column_names: list[str]
    column_dtypes: dict[str, str]
    warnings: list[str]

    @classmethod
    def from_dataframe(
            cls, df: pl.DataFrame, warnings: Optional[list[str]] = None
    ) -> "DataSourceResult":
        return cls(
            dataframe=df,
            row_count=df.height,
            column_count=df.width,
            column_names=df.columns,
            column_dtypes={col: str(df[col].dtype) for col in df.columns},
            warnings=warnings or [],
        )


@dataclass
class SizeEstimate:
    """Estimated size of a query result."""

    estimated_rows: int
    estimated_width_bytes: Optional[int] = None  # Average row width in bytes
    estimated_total_bytes: Optional[int] = None  # estimated_rows * estimated_width_bytes
    estimation_method: str = ""  # "count", "explain_analyze", "unknown"
    is_exact: bool = False
    estimation_time_ms: float = 0.0


class Datasource(ABC):
    """
    Abstract base class for data sources.

    Implementations should:
    1. Return Polars DataFrames
    2. Support size estimation for throttling
    3. Handle timeouts appropriately
    4. Provide clear error messages
    """

    @abstractmethod
    def fetch(
            self,
            query_config: Any,
            bind_variables: Optional[dict[str, Any]] = None,
    ) -> DataSourceResult:
        """
        Fetch data from the source.

        Args:
            query_config: Source-specific query configuration
            bind_variables: Optional variables to bind to the query

        Returns:
            DataSourceResult containing the data as a Polars DataFrame

        Raises:
            DatasourceError: If the fetch operation fails
            ThrottlingError: If throttled due to resource constraints
        """
        pass

    @abstractmethod
    def estimate_size(
            self,
            query_config: Any,
            bind_variables: Optional[dict[str, Any]] = None,
    ) -> SizeEstimate:
        """
        Estimate the size of the result set.

        Args:
            query_config: Source-specific query configuration
            bind_variables: Optional variables to bind to the query

        Returns:
            SizeEstimate with estimated row count

        Raises:
            DatasourceError: If estimation fails
        """
        pass

    @abstractmethod
    def validate_query(
            self,
            query_config: Any,
            bind_variables: Optional[dict[str, Any]] = None,
    ) -> list[str]:
        """
        Validate a query without executing it.

        Args:
            query_config: Source-specific query configuration
            bind_variables: Optional variables to bind to the query

        Returns:
            List of validation error messages (empty if valid)
        """
        pass

    @abstractmethod
    def get_source_type(self) -> str:
        """Return the type identifier for this data source."""
        pass
