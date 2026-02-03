# Configuration Guide

## Overview

Configuration uses a Spring Boot-style property resolution system with three layers of precedence:

1. **Default values** (lowest priority) - Defined in dataclass definitions
2. **Configuration files** - YAML, JSON, or properties files
3. **Environment variables** (highest priority) - SCREAMING_SNAKE_CASE naming

## Configuration File Loading

The application searches for configuration files in this order:

1. File specified in `ML_TRAINING_CONFIG` environment variable
2. `config.yaml` in current directory
3. `config.yml` in current directory
4. `config.json` in current directory
5. `config.properties` in current directory
6. `config.yaml` in the config module directory

## Environment Variable Overrides

Environment variables use **SCREAMING_SNAKE_CASE** naming and map to nested configuration properties using underscores.

### Naming Convention Examples

| Environment Variable                 | Configuration Path                   | Example Value        |
|--------------------------------------|--------------------------------------|----------------------|
| `DATABASE_HOST`                      | `database.host`                      | `localhost`          |
| `DATABASE_PORT`                      | `database.port`                      | `5432`               |
| `DATABASE_PASSWORD`                  | `database.password`                  | `secretpass`         |
| `DATASOURCE_MAX_CONCURRENT_QUERIES`  | `datasource.max_concurrent_queries`  | `10`                 |
| `REDIS_URL`                          | `redis.url`                          | `redis://redis:6379` |
| `PIPELINE_STREAMS_TRAINING_REQUESTS` | `pipeline.streams.training_requests` | `custom:training`    |
| `STORAGE_MODELS_DIRECTORY`           | `storage.models_directory`           | `/var/models`        |
| `TUNING_MAX_TUNING_TIME_SECONDS`     | `tuning.max_tuning_time_seconds`     | `600`                |

### Type Conversion

Environment variable values are automatically converted to appropriate types:

- `"true"` / `"false"` → boolean
- Numeric strings → int or float
- Everything else → string

## Usage Examples

### Using YAML Configuration

```yaml
# config.yaml
database:
  host: localhost
  port: 5432
  database: ml_training
  user: postgres
  password: postgres

datasource:
  max_concurrent_queries: 5

redis:
  url: redis://localhost:6379
```

### Overriding with Environment Variables

```bash
# Override database connection
export DATABASE_HOST=prod-db.example.com
export DATABASE_PORT=5433
export DATABASE_PASSWORD=secure_password

# Override datasource settings
export DATASOURCE_MAX_CONCURRENT_QUERIES=20
export DATASOURCE_QUERY_TIMEOUT_SECONDS=60

# Override Redis connection
export REDIS_URL=redis://redis-cluster:6379

# Start application - env vars take precedence over config.yaml
python app.py
```

### Docker/Kubernetes Examples

```yaml
# Docker Compose
services:
  ml-service:
    image: ml-training-service
    environment:
      DATABASE_HOST: postgres
      DATABASE_PASSWORD: ${DB_PASSWORD}
      DATASOURCE_MAX_CONCURRENT_QUERIES: 20
      REDIS_URL: redis://redis:6379
```

```yaml
# Kubernetes ConfigMap + Secret
apiVersion: v1
kind: ConfigMap
metadata:
  name: ml-service-config
data:
  DATABASE_HOST: "postgres-service"
  DATABASE_PORT: "5432"
  DATASOURCE_MAX_CONCURRENT_QUERIES: "20"
  REDIS_URL: "redis://redis-service:6379"
---
apiVersion: v1
kind: Secret
metadata:
  name: ml-service-secrets
type: Opaque
stringData:
  DATABASE_PASSWORD: "your-secure-password"
```

## Configuration Sections

### Database

```bash
DATABASE_HOST=localhost
DATABASE_PORT=5432
DATABASE_DATABASE=ml_training
DATABASE_USER=postgres
DATABASE_PASSWORD=postgres
DATABASE_MIN_CONNECTIONS=1
DATABASE_MAX_CONNECTIONS=10
```

### Datasource

```bash
DATASOURCE_QUERY_TIMEOUT_SECONDS=30
DATASOURCE_EXPLAIN_ANALYZE_TIMEOUT_SECONDS=5
DATASOURCE_MAX_RESULT_ROWS=1000000
DATASOURCE_MAX_CONCURRENT_QUERIES=5
DATASOURCE_THROTTLE_WAIT_SECONDS=300
```

### Storage

```bash
STORAGE_MODELS_DIRECTORY=./trained_models
STORAGE_CREATE_IF_MISSING=true
```

### Redis

```bash
REDIS_URL=redis://localhost:6379
```

### Tuning

```bash
TUNING_MAX_TUNING_TIME_SECONDS=300
TUNING_DEFAULT_N_TRIALS=50
TUNING_DEFAULT_METRIC=auto
```

### Pipeline

```bash
PIPELINE_STREAMS_TUNING_REQUESTS=ml_training:tuning_requests
PIPELINE_STREAMS_TRAINING_REQUESTS=ml_training:training_requests
PIPELINE_STREAMS_TRAINING_RESULTS=ml_training:training_results
PIPELINE_CONSUMER_GROUPS_TUNING=tuning_workers
PIPELINE_CONSUMER_GROUPS_TRAINING=training_workers
```

### Logging

```bash
LOGGING_LEVEL=INFO
LOGGING_FORMAT="%(asctime)s - %(name)s - %(levelname)s - %(message)s"
```

## Benefits

1. **Production-ready**: Easy to configure for different environments (dev, staging, prod)
2. **Security**: Sensitive values (passwords, API keys) via environment variables, not committed to git
3. **Container-friendly**: Perfect for Docker, Kubernetes, and cloud deployments
4. **Spring-compatible**: Familiar pattern for Java/Spring developers
5. **Override precedence**: Clear and predictable configuration resolution
