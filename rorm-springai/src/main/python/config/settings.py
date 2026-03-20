"""
Configuration management module.

Supports loading from YAML, JSON, or properties files with environment variable overrides.
Environment variables in SCREAMING_SNAKE_CASE override all file-based configuration,
similar to Spring Boot's property resolution.
"""

import json
import os
import yaml
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Optional


@dataclass
class DatabaseConfig:
    host: str = "localhost"
    port: int = 5432
    database: str = "ml_training"
    user: str = "postgres"
    password: str = "postgres"
    min_connections: int = 1
    max_connections: int = 10

    def get_connection_string(self) -> str:
        return f"postgresql://{self.user}:{self.password}@{self.host}:{self.port}/{self.database}"


@dataclass
class DatasourceConfig:
    query_timeout_seconds: int = 30
    explain_analyze_timeout_seconds: int = 5
    # Soft limit: use lazy evaluation when estimated memory exceeds this (bytes)
    lazy_materialization_threshold_bytes: int = 2_147_483_648  # 2GB
    # Hard limit: refuse completely when estimated memory exceeds this (bytes)
    absolute_max_bytes: int = 17_179_869_184  # 16GB
    max_concurrent_queries: int = 5
    throttle_wait_seconds: int = 10


@dataclass
class StorageConfig:
    models_directory: str = "./trained_models"
    create_if_missing: bool = True


@dataclass
class LoggingConfig:
    level: str = "INFO"
    format: str = "%(asctime)s - %(name)s - %(levelname)s - %(message)s"


@dataclass
class TuningConfig:
    max_tuning_time_seconds: int = 300
    default_n_trials: int = 50
    default_metric: str = "auto"


@dataclass
class WorkerPoolConfig:
    max_workers: int = 0
    memory_budget_gb: float = 0.0


@dataclass
class BackpressureConfig:
    enabled: bool = True
    worker_threshold: float = 0.8
    memory_threshold: float = 0.8
    pause_seconds: float = 1.0


@dataclass
class PipelineStreams:
    tuning_requests: str = "ml_training:tuning_requests"
    training_requests: str = "ml_training:training_requests"
    training_results: str = "ml_training:training_results"
    stability_selection_requests: str = "ml_training:stability_selection_requests"
    shap_requests: str = "ml_training:shap_requests"


@dataclass
class PipelineConsumerGroups:
    tuning: str = "tuning_workers"
    training: str = "training_workers"
    stability_selection: str = "analysis_workers"
    shap: str = "shap_workers"


@dataclass
class PipelineConfig:
    streams: PipelineStreams = field(default_factory=PipelineStreams)
    consumer_groups: PipelineConsumerGroups = field(default_factory=PipelineConsumerGroups)


@dataclass
class RedisConfig:
    url: str = "redis://localhost:6379"


@dataclass
class Settings:
    database: DatabaseConfig = field(default_factory=DatabaseConfig)
    datasource: DatasourceConfig = field(default_factory=DatasourceConfig)
    storage: StorageConfig = field(default_factory=StorageConfig)
    logging: LoggingConfig = field(default_factory=LoggingConfig)
    tuning: TuningConfig = field(default_factory=TuningConfig)
    pipeline: PipelineConfig = field(default_factory=PipelineConfig)
    redis: RedisConfig = field(default_factory=RedisConfig)
    worker_pool: WorkerPoolConfig = field(default_factory=WorkerPoolConfig)
    backpressure: BackpressureConfig = field(default_factory=BackpressureConfig)

    def model_dump(self) -> dict[str, Any]:
        """Convert settings to dictionary."""
        from dataclasses import asdict
        return asdict(self)

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> "Settings":
        pipeline_data = data.get("pipeline", {})
        return cls(
            database=DatabaseConfig(**data.get("database", {})),
            datasource=DatasourceConfig(**data.get("datasource", {})),
            storage=StorageConfig(**data.get("storage", {})),
            logging=LoggingConfig(**data.get("logging", {})),
            tuning=TuningConfig(**data.get("tuning", {})),
            pipeline=PipelineConfig(
                streams=PipelineStreams(**pipeline_data.get("streams", {})),
                consumer_groups=PipelineConsumerGroups(**pipeline_data.get("consumer_groups", {}))
            ),
            redis=RedisConfig(**data.get("redis", {})),
            worker_pool=WorkerPoolConfig(**data.get("worker_pool", {})),
            backpressure=BackpressureConfig(**data.get("backpressure", {})),
        )

    @classmethod
    def load_yaml(cls, path: Path) -> "Settings":
        with open(path, "r") as f:
            data = yaml.safe_load(f)
        return cls.from_dict(data or {})

    @classmethod
    def load_json(cls, path: Path) -> "Settings":
        with open(path, "r") as f:
            data = json.load(f)
        return cls.from_dict(data or {})

    @classmethod
    def load_properties(cls, path: Path) -> "Settings":
        data: dict[str, Any] = {}
        with open(path, "r") as f:
            for line in f:
                line = line.strip()
                if not line or line.startswith("#"):
                    continue
                if "=" in line:
                    key, value = line.split("=", 1)
                    keys = key.strip().split(".")
                    current = data
                    for k in keys[:-1]:
                        current = current.setdefault(k, {})
                    final_key = keys[-1]
                    value = value.strip()
                    if value.lower() in ("true", "false"):
                        current[final_key] = value.lower() == "true"
                    elif value.isdigit():
                        current[final_key] = int(value)
                    else:
                        current[final_key] = value
        return cls.from_dict(data)

    @staticmethod
    def _parse_env_value(value: str) -> Any:
        """Parse environment variable value to appropriate type."""
        # Try boolean
        if value.lower() in ("true", "false"):
            return value.lower() == "true"
        # Try integer
        try:
            return int(value)
        except ValueError:
            pass
        # Try float
        try:
            return float(value)
        except ValueError:
            pass
        # Return as string
        return value

    @staticmethod
    def _deep_merge(base: dict, override: dict) -> dict:
        """
        Deep merge override dict into base dict.
        Override values take precedence.
        """
        result = base.copy()
        for key, value in override.items():
            if key in result and isinstance(result[key], dict) and isinstance(value, dict):
                result[key] = Settings._deep_merge(result[key], value)
            else:
                result[key] = value
        return result

    @classmethod
    def _load_env_overrides(cls) -> dict[str, Any]:
        """
        Load configuration overrides from environment variables.

        Environment variables follow Spring Boot convention:
        - SCREAMING_SNAKE_CASE naming
        - Underscores represent nested levels
        - Example: DATABASE_HOST overrides database.host
        - Example: DATASOURCE_MAX_CONCURRENT_QUERIES overrides datasource.max_concurrent_queries
        - Example: PIPELINE_STREAMS_TRAINING_REQUESTS overrides pipeline.streams.training_requests
        """
        overrides: dict[str, Any] = {}

        # Known top-level config sections
        known_prefixes = {
            "DATABASE_",
            "DATASOURCE_",
            "STORAGE_",
            "LOGGING_",
            "TUNING_",
            "PIPELINE_",
            "REDIS_",
            "WORKER_POOL_",
            "BACKPRESSURE_",
        }

        for env_key, env_value in os.environ.items():
            # Check if this env var matches a known config prefix
            matching_prefix = None
            for prefix in known_prefixes:
                if env_key.startswith(prefix):
                    matching_prefix = prefix
                    break

            if matching_prefix:
                # Convert SCREAMING_SNAKE_CASE to nested dict
                # DATABASE_HOST -> database.host
                # PIPELINE_STREAMS_TRAINING_REQUESTS -> pipeline.streams.training_requests
                parts = env_key.lower().split("_")

                # Build nested structure
                current = overrides
                for part in parts[:-1]:
                    if part not in current:
                        current[part] = {}
                    current = current[part]

                # Set the final value with type conversion
                current[parts[-1]] = cls._parse_env_value(env_value)

        return overrides

    @classmethod
    def load(cls, path: Optional[Path] = None) -> "Settings":
        """
        Load configuration with Spring Boot-style precedence:
        1. Default values (in dataclass definitions)
        2. Configuration file (YAML/JSON/properties)
        3. Environment variables (highest priority)

        Environment variables override all file-based configuration.
        """
        # Load from file
        config_data: dict[str, Any] = {}

        if path is None:
            env_path = os.environ.get("ML_TRAINING_CONFIG")
            if env_path:
                path = Path(env_path)
            else:
                default_paths = [
                    Path("config.yaml"),
                    Path("config.yml"),
                    Path("config.json"),
                    Path("config.properties"),
                    Path(__file__).parent / "config.yaml",
                ]
                for p in default_paths:
                    if p.exists():
                        path = p
                        break

        if path and path.exists():
            suffix = path.suffix.lower()
            if suffix in (".yaml", ".yml"):
                with open(path, "r") as f:
                    config_data = yaml.safe_load(f) or {}
            elif suffix == ".json":
                with open(path, "r") as f:
                    config_data = json.load(f) or {}
            elif suffix == ".properties":
                # Reuse properties loading logic
                with open(path, "r") as f:
                    for line in f:
                        line = line.strip()
                        if not line or line.startswith("#"):
                            continue
                        if "=" in line:
                            key, value = line.split("=", 1)
                            keys = key.strip().split(".")
                            current = config_data
                            for k in keys[:-1]:
                                current = current.setdefault(k, {})
                            final_key = keys[-1]
                            current[final_key] = cls._parse_env_value(value.strip())
            else:
                raise ValueError(
                    f"Unsupported configuration file format: {suffix}. "
                    f"Supported formats: .yaml, .yml, .json, .properties"
                )

        # Load and merge environment variable overrides (highest priority)
        env_overrides = cls._load_env_overrides()
        final_config = cls._deep_merge(config_data, env_overrides)

        return cls.from_dict(final_config)


_settings: Optional[Settings] = None


def get_settings() -> Settings:
    global _settings
    if _settings is None:
        _settings = Settings.load()
    return _settings


def reload_settings(path: Optional[Path] = None) -> Settings:
    global _settings
    _settings = Settings.load(path)
    return _settings
