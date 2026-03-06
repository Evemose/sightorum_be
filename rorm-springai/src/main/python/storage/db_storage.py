"""Database storage for trained models and results."""

import json
import re
import uuid
from models.base import TrainedModel, ModelCategory
from psycopg.rows import dict_row
from typing import Optional, Dict, Any, List


class DatabaseModelStorage:
    """
    Database storage for trained models and unsupervised results.

    - Supervised models: Stored in `trained_models` table with serialized model
    - Unsupervised results: Stored in dynamic tables named by UUID
    """

    def __init__(self, connection_pool):
        """
        Initialize database storage.

        Args:
            connection_pool: PostgreSQL connection pool
        """
        self.pool = connection_pool
        self._ensure_schema()

    def _ensure_schema(self):
        """Create necessary database tables."""
        with self.pool.connection() as conn:
            with conn.cursor() as cur:
                # Table for supervised/prediction models
                cur.execute("""
                            create table if not exists trained_models
                            (
                                id               serial primary key,
                                model_uuid       UUID unique  not null,
                                model_name       varchar(255) not null,
                                model_type       varchar(100) not null,
                                model_category   varchar(50)  not null,
                                feature_columns  text[],
                                target_column    varchar(255),
                                training_rows    integer,
                                training_columns integer,
                                model_params     JSONB,
                                training_metrics JSONB,
                                model_binary     BYTEA        not null,
                                created_at       timestamp    not null default now(),
                                unique (model_name, model_type)
                            )
                            """)

                # Index for fast lookups
                cur.execute("""
                            create index if not exists idx_trained_models_uuid
                                on trained_models (model_uuid)
                            """)
                cur.execute("""
                            create index if not exists idx_trained_models_name
                                on trained_models (model_name)
                            """)

                # Table for stability selection runs
                cur.execute("""
                            create table if not exists stability_runs
                            (
                                id              UUID primary key,
                                problem_type    varchar(50) not null,
                                feature_columns text[]      not null,
                                encoded_columns text[]      not null,
                                bootstrap_runs  integer     not null,
                                encoded_data    BYTEA       not null,
                                feature_values  BYTEA       not null,
                                result_summary  JSONB,
                                created_at      timestamp   not null default now()
                            )
                            """)

                # Table for individual bootstrap models
                cur.execute("""
                            create table if not exists stability_run_models
                            (
                                id             serial primary key,
                                run_id         UUID    not null references stability_runs (id) on delete cascade,
                                model_index    integer not null,
                                model_binary   BYTEA   not null,
                                sample_indices BYTEA   not null,
                                unique (run_id, model_index)
                            )
                            """)

                cur.execute("""
                            create index if not exists idx_stability_run_models_run_id
                                on stability_run_models (run_id)
                            """)

                conn.commit()

    def save_supervised_model(self, trained_model: TrainedModel) -> str:
        """
        Save a supervised/prediction model to database.

        Args:
            trained_model: Trained model instance

        Returns:
            Model UUID for predictions
        """
        import joblib
        from io import BytesIO

        # Serialize model to bytes
        buffer = BytesIO()
        joblib.dump(trained_model.model, buffer)
        model_binary = buffer.getvalue()

        # Generate UUID
        model_uuid = uuid.uuid4()

        with self.pool.connection() as conn:
            with conn.cursor() as cur:
                cur.execute("""
                            insert into trained_models (model_uuid, model_name, model_type, model_category,
                                                        feature_columns, target_column, training_rows, training_columns,
                                                        model_params, training_metrics, model_binary)
                            values (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
                            on conflict (model_name, model_type)
                                do update set model_uuid       = EXCLUDED.model_uuid,
                                              feature_columns  = EXCLUDED.feature_columns,
                                              target_column    = EXCLUDED.target_column,
                                              training_rows    = EXCLUDED.training_rows,
                                              training_columns = EXCLUDED.training_columns,
                                              model_params     = EXCLUDED.model_params,
                                              training_metrics = EXCLUDED.training_metrics,
                                              model_binary     = EXCLUDED.model_binary,
                                              created_at       = now()
                            returning model_uuid
                            """, (
                                str(model_uuid),
                                trained_model.model_name,
                                trained_model.model_type,
                                trained_model.category.value,
                                trained_model.feature_columns,
                                trained_model.target_column,
                                trained_model.training_rows,
                                trained_model.training_columns,
                                json.dumps(trained_model.model_params) if trained_model.model_params else None,
                                json.dumps(trained_model.training_metrics) if trained_model.training_metrics else None,
                                model_binary
                            ))

                result = cur.fetchone()
                conn.commit()

                return str(result[0])

    def load_supervised_model(self, model_uuid: str) -> Optional[TrainedModel]:
        """
        Load a supervised model by UUID.

        Args:
            model_uuid: Model UUID

        Returns:
            TrainedModel instance or None if not found
        """
        import joblib
        from io import BytesIO

        with self.pool.connection() as conn:
            with conn.cursor(row_factory=dict_row) as cur:
                cur.execute("""
                            select *
                            from trained_models
                            where model_uuid = %s
                            """, (model_uuid,))

                row = cur.fetchone()

                if not row:
                    return None

                # Deserialize model
                buffer = BytesIO(row['model_binary'])
                model = joblib.load(buffer)

                # Reconstruct TrainedModel
                trained_model = TrainedModel(
                    model=model,
                    model_name=row['model_name'],
                    model_type=row['model_type'],
                    category=ModelCategory(row['model_category']),
                    feature_columns=row['feature_columns'],
                    target_column=row['target_column'],
                    training_rows=row['training_rows'],
                    training_columns=row['training_columns'],
                    model_params=row['model_params'],
                    training_metrics=row['training_metrics'],
                    created_at=row['created_at']
                )

                return trained_model

    def get_supervised_model_metadata(self, model_uuid: str) -> Optional[Dict[str, Any]]:
        """Get metadata for a supervised model without loading the model binary."""
        with self.pool.connection() as conn:
            with conn.cursor(row_factory=dict_row) as cur:
                cur.execute("""
                            select model_uuid,
                                   model_name,
                                   model_type,
                                   model_category,
                                   feature_columns,
                                   target_column,
                                   training_rows,
                                   training_columns,
                                   model_params,
                                   training_metrics,
                                   created_at
                            from trained_models
                            where model_uuid = %s
                            """, (model_uuid,))

                row = cur.fetchone()
                return dict(row) if row else None

    def list_supervised_models(self, limit: int = 100) -> List[Dict[str, Any]]:
        """List all supervised models."""
        with self.pool.connection() as conn:
            with conn.cursor(row_factory=dict_row) as cur:
                cur.execute("""
                            select model_uuid,
                                   model_name,
                                   model_type,
                                   model_category,
                                   feature_columns,
                                   target_column,
                                   training_rows,
                                   training_columns,
                                   model_params,
                                   training_metrics,
                                   created_at
                            from trained_models
                            order by created_at desc
                            limit %s
                            """, (limit,))

                return [dict(row) for row in cur.fetchall()]

    def delete_supervised_model(self, model_uuid: str) -> bool:
        """Delete a supervised model."""
        with self.pool.connection() as conn:
            with conn.cursor() as cur:
                cur.execute("""
                            delete
                            from trained_models
                            where model_uuid = %s
                            """, (model_uuid,))
                conn.commit()
                return cur.rowcount > 0

    def save_unsupervised_results(
            self,
            _trained_model: TrainedModel,
            results_dataframe
    ) -> str:
        """
        Save unsupervised model results to a dynamic table.

        Args:
            _trained_model: Trained model instance (not used, reserved for metadata)
            results_dataframe: Polars DataFrame with results

        Returns:
            UUID identifier for retrieving results
        """
        # Generate UUID for table name
        results_uuid = uuid.uuid4()
        table_name = f"results_{str(results_uuid).replace('-', '_')}"

        with self.pool.connection() as conn:
            with conn.cursor() as cur:
                # Convert Polars to Pandas for easier SQL insertion
                df = results_dataframe.to_pandas()

                # Create table dynamically based on dataframe columns
                columns_def = []
                for col in df.columns:
                    dtype = df[col].dtype
                    if dtype == 'int64':
                        sql_type = 'BIGINT'
                    elif dtype == 'float64':
                        sql_type = 'DOUBLE PRECISION'
                    elif dtype == 'bool':
                        sql_type = 'BOOLEAN'
                    else:
                        sql_type = 'TEXT'

                    # Sanitize column name
                    safe_col = re.sub(r'[^a-zA-Z0-9_]', '_', col)
                    columns_def.append(f'"{safe_col}" {sql_type}')

                # Create table
                create_sql = f"""
                    CREATE TABLE "{table_name}" (
                        id SERIAL PRIMARY KEY,
                        {', '.join(columns_def)},
                        created_at TIMESTAMP DEFAULT NOW()
                    )
                """
                cur.execute(create_sql)

                # Insert data
                if len(df) > 0:
                    columns = [re.sub(r'[^a-zA-Z0-9_]', '_', col) for col in df.columns]
                    placeholders = ', '.join(['%s'] * len(columns))
                    insert_sql = f"""
                        INSERT INTO "{table_name}" ({', '.join(f'"{col}"' for col in columns)})
                        VALUES ({placeholders})
                    """

                    # Bulk insert
                    for _, row in df.iterrows():
                        cur.execute(insert_sql, tuple(row))

                conn.commit()

                return str(results_uuid)

    def get_unsupervised_results(
            self,
            results_uuid: str,
            limit: int = 1000,
            offset: int = 0
    ) -> Optional[Dict[str, Any]]:
        """
        Retrieve unsupervised results by UUID.

        Args:
            results_uuid: Results UUID
            limit: Maximum rows to return
            offset: Offset for pagination

        Returns:
            Dictionary with results data and metadata
        """
        # Validate UUID format to prevent SQL injection
        if not self._is_valid_uuid(results_uuid):
            return None

        table_name = f"results_{results_uuid.replace('-', '_')}"

        with self.pool.connection() as conn:
            with conn.cursor(row_factory=dict_row) as cur:
                # Check if table exists
                cur.execute("""
                            select exists (select
                                           from information_schema.tables
                                           where table_name = %s)
                            """, (table_name,))

                if not cur.fetchone()['exists']:
                    return None

                # Get total count
                cur.execute(f'SELECT COUNT(*) as count FROM "{table_name}"')
                total_rows = cur.fetchone()['count']

                # Get data
                cur.execute(f"""
                    SELECT * FROM "{table_name}"
                    ORDER BY id
                    LIMIT %s OFFSET %s
                """, (limit, offset))

                rows = [dict(row) for row in cur.fetchall()]

                return {
                    "results_uuid": results_uuid,
                    "total_rows": total_rows,
                    "returned_rows": len(rows),
                    "limit": limit,
                    "offset": offset,
                    "data": rows
                }

    def list_unsupervised_results(self, limit: int = 100) -> List[Dict[str, Any]]:
        """
        List all unsupervised results tables.

        Returns:
            List of dictionaries with results_uuid and metadata
        """
        with self.pool.connection() as conn:
            with conn.cursor(row_factory=dict_row) as cur:
                # Find all tables matching results_* pattern
                cur.execute("""
                            select table_name,
                                   pg_size_pretty(pg_total_relation_size(quote_ident(table_name))) as size
                            from information_schema.tables
                            where table_schema = 'public'
                              and table_name like 'results_%%'
                            order by table_name desc
                            limit %s
                            """, (limit,))

                results = []
                for row in cur.fetchall():
                    table_name = row['table_name']
                    # Extract UUID from table name
                    uuid_str = table_name.replace('results_', '').replace('_', '-')

                    # Get row count and creation time
                    cur.execute(f"""
                        SELECT
                            COUNT(*) as row_count,
                            MIN(created_at) as created_at
                        FROM "{table_name}"
                    """)
                    stats = cur.fetchone()

                    results.append({
                        "results_uuid": uuid_str,
                        "table_name": table_name,
                        "row_count": stats['row_count'],
                        "size": row['size'],
                        "created_at": stats['created_at'].isoformat() if stats['created_at'] else None
                    })

                return results

    def delete_unsupervised_results(self, results_uuid: str) -> bool:
        """Delete unsupervised results table."""
        if not self._is_valid_uuid(results_uuid):
            return False

        table_name = f"results_{results_uuid.replace('-', '_')}"

        with self.pool.connection() as conn:
            with conn.cursor() as cur:
                # Check if table exists first
                cur.execute("""
                            select exists (select
                                           from information_schema.tables
                                           where table_name = %s)
                            """, (table_name,))

                if not cur.fetchone()[0]:
                    return False

                # Drop table
                cur.execute(f'DROP TABLE IF EXISTS "{table_name}"')
                conn.commit()
                return True

    # ========== Stability Run Methods ==========

    def save_stability_run(
            self,
            run_id: str,
            problem_type: str,
            feature_columns: list[str],
            encoded_columns: list[str],
            bootstrap_runs: int,
            encoded_data: bytes,
            feature_values: bytes,
            result_summary: Optional[Dict[str, Any]] = None,
    ) -> str:
        with self.pool.connection() as conn:
            with conn.cursor() as cur:
                cur.execute("""
                            insert into stability_runs
                            (id, problem_type, feature_columns, encoded_columns,
                             bootstrap_runs, encoded_data, feature_values, result_summary)
                            values (%s, %s, %s, %s, %s, %s, %s, %s)
                            """, (
                                run_id, problem_type, feature_columns, encoded_columns,
                                bootstrap_runs, encoded_data, feature_values,
                                json.dumps(result_summary) if result_summary else None,
                            ))
                conn.commit()
        return run_id

    def save_stability_run_models_batch(
            self,
            run_id: str,
            models: List[tuple],
    ):
        """Batch insert (model_index, model_binary, sample_indices_binary) tuples."""
        with self.pool.connection() as conn:
            with conn.cursor() as cur:
                cur.executemany(
                    """
                    insert into stability_run_models (run_id, model_index, model_binary, sample_indices)
                    values (%s, %s, %s, %s)
                    """,
                    [(run_id, idx, model_bin, indices_bin) for idx, model_bin, indices_bin in models],
                )
                conn.commit()

    def load_stability_run(self, run_id: str) -> Optional[Dict[str, Any]]:
        if not self._is_valid_uuid(run_id):
            return None
        with self.pool.connection() as conn:
            with conn.cursor(row_factory=dict_row) as cur:
                cur.execute("""
                            select id,
                                   problem_type,
                                   feature_columns,
                                   encoded_columns,
                                   bootstrap_runs,
                                   encoded_data,
                                   feature_values,
                                   result_summary,
                                   created_at
                            from stability_runs
                            where id = %s
                            """, (run_id,))
                row = cur.fetchone()
                return dict(row) if row else None

    def load_stability_run_model(self, run_id: str, model_index: int) -> Optional[Dict[str, Any]]:
        """Load a single model by run_id and model_index."""
        with self.pool.connection() as conn:
            with conn.cursor(row_factory=dict_row) as cur:
                cur.execute("""
                            select model_binary, sample_indices
                            from stability_run_models
                            where run_id = %s
                              and model_index = %s
                            """, (run_id, model_index))
                row = cur.fetchone()
                return dict(row) if row else None

    def count_stability_run_models(self, run_id: str) -> int:
        with self.pool.connection() as conn:
            with conn.cursor() as cur:
                cur.execute("""
                            select count(*)
                            from stability_run_models
                            where run_id = %s
                            """, (run_id,))
                return cur.fetchone()[0]

    def list_stability_runs(self, limit: int = 100) -> List[Dict[str, Any]]:
        with self.pool.connection() as conn:
            with conn.cursor(row_factory=dict_row) as cur:
                cur.execute("""
                            select id,
                                   problem_type,
                                   feature_columns,
                                   bootstrap_runs,
                                   result_summary,
                                   created_at
                            from stability_runs
                            order by created_at desc
                            limit %s
                            """, (limit,))
                return [dict(row) for row in cur.fetchall()]

    def delete_stability_run(self, run_id: str) -> bool:
        if not self._is_valid_uuid(run_id):
            return False
        with self.pool.connection() as conn:
            with conn.cursor() as cur:
                cur.execute("delete from stability_runs where id = %s", (run_id,))
                conn.commit()
                return cur.rowcount > 0

    @staticmethod
    def _is_valid_uuid(uuid_string: str) -> bool:
        """Validate UUID format to prevent SQL injection."""
        try:
            uuid.UUID(uuid_string)
            return True
        except (ValueError, AttributeError):
            return False
