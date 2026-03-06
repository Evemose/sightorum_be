"""
PostgreSQL data source implementation.

Provides SQL-based data fetching with:
- Connection pooling
- Query size estimation (COUNT or EXPLAIN ANALYZE)
- Throttling integration
- Timeout management
"""

import logging
import polars as pl
import psycopg
import re
import sqlglot
import time
from config.settings import DatabaseConfig
from core.exceptions import DatasourceError
from psycopg.types.numeric import NumericLoader
from psycopg_pool import ConnectionPool
from sqlglot import exp
from typing import Any, Optional

from .interface import Datasource, DataSourceResult, SizeEstimate
from .throttler import QueryThrottler

logger = logging.getLogger(__name__)


class _FloatNumericLoader(NumericLoader):
    """Return PostgreSQL numeric/decimal values as Python float instead of Decimal.

    Prevents Polars from creating Object-dtype columns for numeric(p,s) fields,
    which would cause pd.get_dummies() to one-hot encode every distinct value.
    """

    def load(self, data: bytes) -> float:
        return float(super().load(data))


def _configure_connection(conn: psycopg.Connection) -> None:
    """Configure each pooled connection to return floats for numeric types."""
    conn.adapters.register_loader("numeric", _FloatNumericLoader)


def create_connection_pool(config: DatabaseConfig) -> ConnectionPool:
    """
    Create a psycopg connection pool.

    Args:
        config: Database configuration

    Returns:
        ConnectionPool instance
    """
    return ConnectionPool(
        config.get_connection_string(),
        min_size=config.min_connections,
        max_size=config.max_connections,
        configure=_configure_connection,
        open=True,
    )


class PostgreSQLDatasource(Datasource):
    """
    PostgreSQL data source implementation.

    Features:
    - Uses psycopg3 with connection pooling
    - Estimates result size before execution
    - Integrates with throttler for resource management
    - Converts results to Polars DataFrames
    """

    def __init__(
            self,
            pool: ConnectionPool,
            throttler: QueryThrottler,
            query_timeout: int = 30,
            explain_timeout: int = 5,
            lazy_threshold_bytes: int = 2_147_483_648,  # 2GB
            absolute_max_bytes: int = 17_179_869_184,  # 16GB
    ):
        """
        Initialize PostgreSQL data source.

        Args:
            pool: Connection pool
            throttler: Query throttler for concurrency control
            query_timeout: Query execution timeout in seconds
            explain_timeout: EXPLAIN ANALYZE timeout in seconds
            lazy_threshold_bytes: Soft limit - use lazy evaluation above this
            absolute_max_bytes: Hard limit - refuse queries above this
        """
        self._pool = pool
        self._throttler = throttler
        self._query_timeout = query_timeout
        self._explain_timeout = explain_timeout
        self._lazy_threshold_bytes = lazy_threshold_bytes
        self._absolute_max_bytes = absolute_max_bytes

    def get_source_type(self) -> str:
        return "postgresql"

    def validate_query(
            self,
            query_config: str,
            bind_variables: Optional[dict[str, Any]] = None,
    ) -> list[str]:
        """Validate SQL query syntax and permissions."""
        errors = []

        if not query_config or not query_config.strip():
            errors.append("SQL query cannot be empty")
            return errors

        normalized = query_config.strip().upper()

        if not normalized.startswith("SELECT") and not normalized.startswith("WITH"):
            errors.append("Only SELECT statements are allowed")

        # Check for multiple statements
        if ";" in query_config[:-1]:  # Allow trailing semicolon
            errors.append("Multiple statements are not allowed")

        # Try to prepare the statement to validate syntax
        try:
            with self._pool.connection() as conn:
                with conn.cursor() as cur:
                    # Use PREPARE to validate without executing
                    prepared_sql, params = self._prepare_query(
                        query_config, bind_variables or {}
                    )
                    explain_sql = self._wrap_with_explain(prepared_sql)
                    cur.execute(explain_sql, params)
        except psycopg.Error as e:
            errors.append(f"SQL syntax error: {str(e)}")

        return errors

    def estimate_size(
            self,
            query_config: str,
            bind_variables: Optional[dict[str, Any]] = None,
    ) -> SizeEstimate:
        """
        Estimate result set size using EXPLAIN ANALYZE or COUNT.

        Attempts EXPLAIN ANALYZE first (faster for complex queries, provides row width),
        falls back to COUNT wrapper for simpler queries (doesn't provide width).
        """
        bind_vars = bind_variables or {}
        start_time = time.time()

        # Try EXPLAIN ANALYZE first
        try:
            estimate = self._estimate_with_explain(query_config, bind_vars)
            if estimate is not None:
                rows, width = estimate
                total_bytes = rows * width
                return SizeEstimate(
                    estimated_rows=rows,
                    estimated_width_bytes=width,
                    estimated_total_bytes=total_bytes,
                    estimation_method="explain_analyze",
                    is_exact=False,
                    estimation_time_ms=(time.time() - start_time) * 1000,
                )
        except (psycopg.Error, TimeoutError):
            pass

        # Fall back to COUNT wrapper (won't have width estimate)
        try:
            count = self._estimate_with_count(query_config, bind_vars)
            return SizeEstimate(
                estimated_rows=count,
                estimated_width_bytes=None,
                estimated_total_bytes=None,
                estimation_method="count",
                is_exact=True,
                estimation_time_ms=(time.time() - start_time) * 1000,
            )
        except psycopg.Error as e:
            raise DatasourceError.query_failed(
                query_config, f"Failed to estimate query size: {str(e)}"
            )

    def _estimate_with_explain(
            self, query: str, bind_vars: dict[str, Any]
    ) -> Optional[tuple[int, int]]:
        """
        Estimate rows and width using EXPLAIN ANALYZE.

        Returns:
            Tuple of (estimated_rows, estimated_width_bytes) or None if estimation fails
        """
        prepared_sql, params = self._prepare_query(query, bind_vars)
        explain_sql = self._wrap_with_explain(prepared_sql, analyze=True)

        with self._pool.connection() as conn:
            conn.execute(f"SET statement_timeout = {self._explain_timeout * 1000}")
            try:
                with conn.cursor() as cur:
                    cur.execute(explain_sql, params)
                    result = cur.fetchall()

                    # Parse the EXPLAIN output for row and width estimates
                    # Example: "Seq Scan on table  (cost=0.00..10.00 rows=100 width=50)"
                    rows_estimate = None
                    width_estimate = None

                    for row in result:
                        line = row[0]
                        # Look for "rows=X" pattern
                        rows_match = re.search(r"rows=(\d+)", line)
                        if rows_match:
                            rows_estimate = int(rows_match.group(1))

                        # Look for "width=X" pattern (average row width in bytes)
                        width_match = re.search(r"width=(\d+)", line)
                        if width_match:
                            width_estimate = int(width_match.group(1))

                        # Return when we find the first node with both estimates
                        if rows_estimate is not None and width_estimate is not None:
                            return (rows_estimate, width_estimate)
            except psycopg.Error:
                conn.rollback()
                raise
            finally:
                try:
                    conn.execute("RESET statement_timeout")
                except psycopg.Error:
                    conn.rollback()

        return None

    def _estimate_with_count(self, query: str, bind_vars: dict[str, Any]) -> int:
        """Estimate rows using COUNT wrapper built with sqlglot."""
        prepared_sql, params = self._prepare_query(query, bind_vars)
        count_query = self._wrap_with_count(prepared_sql)

        with self._pool.connection() as conn:
            conn.execute(f"SET statement_timeout = {self._explain_timeout * 1000}")
            try:
                with conn.cursor() as cur:
                    cur.execute(count_query, params)
                    result = cur.fetchone()
                    return result[0] if result else 0
            except psycopg.Error:
                conn.rollback()
                raise
            finally:
                try:
                    conn.execute("RESET statement_timeout")
                except psycopg.Error:
                    conn.rollback()

    def fetch(
            self,
            query_config: str,
            bind_variables: Optional[dict[str, Any]] = None,
    ) -> DataSourceResult:
        """
        Fetch data from PostgreSQL and return as Polars DataFrame.

        Steps:
        1. Acquire throttler slot
        2. Estimate result size (rows and memory)
        3. Check against hard limit (refuse if exceeded)
        4. Check against soft limit (use lazy evaluation if exceeded)
        5. Execute query
        6. Convert to Polars DataFrame (eager or lazy based on limits)
        """
        bind_vars = bind_variables or {}
        warnings: list[str] = []

        # Acquire throttler slot
        with self._throttler.acquire():
            # Estimate size first
            estimate = self.estimate_size(query_config, bind_vars)

            self.ensure_hard_limit(estimate)

            # Check hard limit first (absolute refusal)
            use_lazy = self.should_use_lazy(estimate, warnings)

            # Execute the actual query
            prepared_sql, params = self._prepare_query(query_config, bind_vars)

            try:
                with self._pool.connection() as conn:
                    conn.execute(
                        f"SET statement_timeout = {self._query_timeout * 1000}"
                    )
                    try:
                        with conn.cursor() as cur:
                            cur.execute(prepared_sql, params)
                            columns = [desc.name for desc in cur.description]

                            if use_lazy:
                                # Use lazy evaluation - fetch in chunks
                                # For now, we still fetch all rows but could implement streaming
                                # This is a placeholder for future optimization
                                rows = cur.fetchall()
                                warnings.append(
                                    "Note: Lazy evaluation is enabled but full implementation "
                                    "requires streaming support. Data loaded into memory."
                                )
                            else:
                                rows = cur.fetchall()
                    except psycopg.Error:
                        conn.rollback()
                        raise
                    finally:
                        try:
                            conn.execute("RESET statement_timeout")
                        except psycopg.Error:
                            conn.rollback()

            except psycopg.errors.QueryCanceled:
                raise DatasourceError.query_timeout(self._query_timeout)
            except psycopg.Error as e:
                raise DatasourceError.query_failed(query_config, str(e))

            # Convert to Polars DataFrame
            if not rows:
                # Create empty DataFrame with correct schema
                df = pl.DataFrame({col: [] for col in columns})
            else:
                df = pl.DataFrame(rows, schema=columns, orient="row")

            return DataSourceResult.from_dataframe(df, warnings)

    def should_use_lazy(self, estimate: SizeEstimate, warnings: list[str]) -> bool:
        use_lazy = False
        if estimate.estimated_total_bytes is not None:
            # Check soft limit (trigger lazy evaluation)
            if estimate.estimated_total_bytes > self._lazy_threshold_bytes:
                use_lazy = True
                size_gb = estimate.estimated_total_bytes / (1024 ** 3)
                threshold_gb = self._lazy_threshold_bytes / (1024 ** 3)
                warnings.append(
                    f"Query result estimated at {size_gb:.2f}GB "
                    f"(exceeds {threshold_gb:.2f}GB threshold). "
                    f"Using lazy evaluation to avoid loading entire dataset into memory."
                )
        return use_lazy

    def ensure_hard_limit(self, estimate: SizeEstimate):
        if estimate.estimated_total_bytes is not None and estimate.estimated_total_bytes > self._absolute_max_bytes:
            size_gb = estimate.estimated_total_bytes / (1024 ** 3)
            limit_gb = self._absolute_max_bytes / (1024 ** 3)
            raise DatasourceError.result_too_large(
                estimate.estimated_rows,
                f"Estimated memory: {size_gb:.2f}GB exceeds absolute limit of {limit_gb:.2f}GB. "
                f"This would likely cause OOM errors."
            )

    @staticmethod
    def _wrap_with_explain(sql: str, analyze: bool = False) -> str:
        """
        Wrap a SQL query with EXPLAIN (or EXPLAIN ANALYZE) using sqlglot.

        Args:
            sql: The SQL query string (may contain %(param)s placeholders)
            analyze: If True, use EXPLAIN ANALYZE

        Returns:
            The EXPLAIN-wrapped SQL string
        """
        prefix = "EXPLAIN ANALYZE" if analyze else "EXPLAIN"
        try:
            # sqlglot can't parse psycopg %(name)s params, so we use raw prefix
            parsed = sqlglot.parse_one(sql, dialect="postgres")
            return f"{prefix} {parsed.sql(dialect='postgres')}"
        except Exception:
            logger.debug("sqlglot parse failed for EXPLAIN wrapping, using raw prefix")
            return f"{prefix} {sql}"

    @staticmethod
    def _wrap_with_count(sql: str) -> str:
        """
        Wrap a SQL query with SELECT COUNT(*) using sqlglot.

        Args:
            sql: The SQL query string (may contain %(param)s placeholders)

        Returns:
            The COUNT-wrapped SQL string
        """
        try:
            parsed = sqlglot.parse_one(sql, dialect="postgres")
            count_expr = (
                exp.select(exp.Count(this=exp.Star()))
                .from_(parsed.subquery("count_subquery"))
            )
            return count_expr.sql(dialect="postgres")
        except Exception:
            logger.debug("sqlglot parse failed for COUNT wrapping, using string fallback")
            return f"SELECT COUNT(*) FROM ({sql}) AS count_subquery"

    def _prepare_query(
            self, query: str, bind_vars: dict[str, Any]
    ) -> tuple[str, dict[str, Any]]:
        """
        Prepare query with named parameters for psycopg3.

        Converts :param_name style to %(param_name)s style.
        """
        # Convert :param to %(param)s for psycopg
        prepared = re.sub(r":(\w+)", r"%(\1)s", query)
        return prepared, bind_vars


class SQLDatasourceFactory:
    """Factory for creating SQL data source instances."""

    @staticmethod
    def create_postgresql(
            config: DatabaseConfig,
            throttler: QueryThrottler,
            query_timeout: int = 30,
            explain_timeout: int = 5,
            lazy_threshold_bytes: int = 2_147_483_648,
            absolute_max_bytes: int = 17_179_869_184,
    ) -> PostgreSQLDatasource:
        """Create a PostgreSQL data source."""
        pool = create_connection_pool(config)
        return PostgreSQLDatasource(
            pool=pool,
            throttler=throttler,
            query_timeout=query_timeout,
            explain_timeout=explain_timeout,
            lazy_threshold_bytes=lazy_threshold_bytes,
            absolute_max_bytes=absolute_max_bytes,
        )
