"""
Test script to verify Spring Boot-style environment variable overrides.

Run this script to see how environment variables override configuration files.
"""

import os
from config.settings import Settings


def test_env_overrides():
    """Test environment variable overrides."""
    print("=" * 80)
    print("Spring Boot-Style Environment Variable Override Test")
    print("=" * 80)
    print()

    # Test 1: Load default config
    print("Test 1: Loading default configuration from config.yaml")
    print("-" * 80)
    settings = Settings.load()
    print(f"Database host: {settings.database.host}")
    print(f"Database port: {settings.database.port}")
    print(f"Datasource max concurrent queries: {settings.datasource.max_concurrent_queries}")
    print(f"Redis URL: {settings.redis.url}")
    print(f"Pipeline training stream: {settings.pipeline.streams.training_requests}")
    print()

    # Test 2: Override with environment variables
    print("Test 2: Setting environment variable overrides")
    print("-" * 80)
    os.environ["DATABASE_HOST"] = "prod-database.example.com"
    os.environ["DATABASE_PORT"] = "5433"
    os.environ["DATABASE_PASSWORD"] = "super_secret_password"
    os.environ["DATASOURCE_MAX_CONCURRENT_QUERIES"] = "25"
    os.environ["REDIS_URL"] = "redis://redis-cluster:6380/1"
    os.environ["PIPELINE_STREAMS_TRAINING_REQUESTS"] = "production:ml:training"
    os.environ["TUNING_MAX_TUNING_TIME_SECONDS"] = "600"

    print("Set environment variables:")
    print("  DATABASE_HOST=prod-database.example.com")
    print("  DATABASE_PORT=5433")
    print("  DATABASE_PASSWORD=super_secret_password")
    print("  DATASOURCE_MAX_CONCURRENT_QUERIES=25")
    print("  REDIS_URL=redis://redis-cluster:6380/1")
    print("  PIPELINE_STREAMS_TRAINING_REQUESTS=production:ml:training")
    print("  TUNING_MAX_TUNING_TIME_SECONDS=600")
    print()

    # Reload settings to pick up env vars
    settings = Settings.load()

    print("Configuration after environment variable overrides:")
    print(f"  Database host: {settings.database.host}")
    print(f"  Database port: {settings.database.port}")
    print(f"  Database password: {'*' * len(settings.database.password)}")
    print(f"  Datasource max concurrent queries: {settings.datasource.max_concurrent_queries}")
    print(f"  Redis URL: {settings.redis.url}")
    print(f"  Pipeline training stream: {settings.pipeline.streams.training_requests}")
    print(f"  Tuning max time: {settings.tuning.max_tuning_time_seconds}s")
    print()

    # Test 3: Verify types
    print("Test 3: Type verification")
    print("-" * 80)
    print(f"  database.port type: {type(settings.database.port).__name__} (should be int)")
    print(f"  database.port value: {settings.database.port}")
    print(
        f"  datasource.max_concurrent_queries type: {type(settings.datasource.max_concurrent_queries).__name__} (should be int)")
    print(f"  datasource.max_concurrent_queries value: {settings.datasource.max_concurrent_queries}")
    print(
        f"  tuning.max_tuning_time_seconds type: {type(settings.tuning.max_tuning_time_seconds).__name__} (should be int)")
    print(f"  tuning.max_tuning_time_seconds value: {settings.tuning.max_tuning_time_seconds}")
    print()

    # Verify assertions
    assert settings.database.host == "prod-database.example.com", "DATABASE_HOST override failed"
    assert settings.database.port == 5433, "DATABASE_PORT override failed"
    assert isinstance(settings.database.port, int), "DATABASE_PORT type conversion failed"
    assert settings.database.password == "super_secret_password", "DATABASE_PASSWORD override failed"
    assert settings.datasource.max_concurrent_queries == 25, "DATASOURCE_MAX_CONCURRENT_QUERIES override failed"
    assert isinstance(settings.datasource.max_concurrent_queries,
                      int), "DATASOURCE_MAX_CONCURRENT_QUERIES type conversion failed"
    assert settings.redis.url == "redis://redis-cluster:6380/1", "REDIS_URL override failed"
    assert settings.pipeline.streams.training_requests == "production:ml:training", "PIPELINE_STREAMS_TRAINING_REQUESTS override failed"
    assert settings.tuning.max_tuning_time_seconds == 600, "TUNING_MAX_TUNING_TIME_SECONDS override failed"

    print("✅ All tests passed!")
    print()
    print("=" * 80)
    print("Summary")
    print("=" * 80)
    print("Environment variables successfully override configuration file values.")
    print("Type conversion works correctly (strings → int/bool/float as needed).")
    print("Nested properties are properly handled with underscore notation.")
    print()

    # Cleanup
    del os.environ["DATABASE_HOST"]
    del os.environ["DATABASE_PORT"]
    del os.environ["DATABASE_PASSWORD"]
    del os.environ["DATASOURCE_MAX_CONCURRENT_QUERIES"]
    del os.environ["REDIS_URL"]
    del os.environ["PIPELINE_STREAMS_TRAINING_REQUESTS"]
    del os.environ["TUNING_MAX_TUNING_TIME_SECONDS"]


if __name__ == "__main__":
    test_env_overrides()
