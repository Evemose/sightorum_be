"""
Comprehensive Dependency Injection container.

All application components are wired through this container
using declarative configuration.
"""

from config.settings import Settings
from dependency_injector import containers, providers


class ApplicationContainer(containers.DeclarativeContainer):
    """
    Main DI container for the entire application.

    All services, repositories, and infrastructure components
    are configured here declaratively.
    """

    wiring_config = containers.WiringConfiguration(
        packages=["config", "core", "datasource", "dto", "events", "models", "processing", "service", "storage"],
    )

    # ========== Configuration ==========
    config = providers.Singleton(Settings.load)

    # ========== Database Connection Pool ==========
    db_pool = providers.Resource(
        lambda cfg: _create_db_pool(cfg),
        cfg=config,
    )

    # ========== Throttler ==========
    query_throttler = providers.Singleton(
        lambda cfg: _create_throttler(cfg),
        cfg=config,
    )

    async_throttler = providers.Singleton(
        lambda throttler: _create_async_throttler(throttler),
        throttler=query_throttler,
    )

    # ========== Datasource ==========
    datasource = providers.Factory(
        lambda cfg, pool, throttler: _create_datasource(cfg, pool, throttler),
        cfg=config,
        pool=db_pool,
        throttler=query_throttler,
    )

    # ========== Model Trainers ==========
    # Clustering
    kmeans_trainer = providers.Factory(lambda: _import_class("models.clustering.kmeans", "KMeansTrainer"))
    dbscan_trainer = providers.Factory(lambda: _import_class("models.clustering.dbscan", "DBSCANTrainer"))
    agglomerative_trainer = providers.Factory(
        lambda: _import_class("models.clustering.agglomerative", "AgglomerativeTrainer"))

    # Regression
    linear_regression_trainer = providers.Factory(
        lambda: _import_class("models.regression.linear", "LinearRegressionTrainer"))
    ridge_regression_trainer = providers.Factory(
        lambda: _import_class("models.regression.ridge", "RidgeRegressionTrainer"))
    random_forest_regressor_trainer = providers.Factory(
        lambda: _import_class("models.regression.random_forest", "RandomForestRegressorTrainer"))
    lgbm_regressor_trainer = providers.Factory(
        lambda: _import_class("models.regression.lgbm_regressor", "LGBMRegressorTrainer"))

    # Classification
    logistic_regression_trainer = providers.Factory(
        lambda: _import_class("models.classification.logistic", "LogisticRegressionTrainer"))
    random_forest_classifier_trainer = providers.Factory(
        lambda: _import_class("models.classification.random_forest", "RandomForestClassifierTrainer"))
    svm_classifier_trainer = providers.Factory(
        lambda: _import_class("models.classification.svm", "SVMClassifierTrainer"))
    lgbm_classifier_trainer = providers.Factory(
        lambda: _import_class("models.classification.lgbm_classifier", "LGBMClassifierTrainer"))

    # Dimensionality
    pca_trainer = providers.Factory(lambda: _import_class("models.dimensionality.pca", "PCATrainer"))
    tsne_trainer = providers.Factory(lambda: _import_class("models.dimensionality.tsne", "TSNETrainer"))

    # Temporal
    arima_trainer = providers.Factory(lambda: _import_class("models.temporal.arima", "ARIMATrainer"))
    sarimax_trainer = providers.Factory(lambda: _import_class("models.temporal.sarimax", "SARIMAXTrainer"))

    # Causal
    dowhy_trainer = providers.Factory(lambda: _import_class("models.causal.dowhy", "DoWhyTrainer"))
    causal_impact_trainer = providers.Factory(
        lambda: _import_class("models.causal.causal_impact", "CausalImpactTrainer"))

    # Association
    apriori_trainer = providers.Factory(lambda: _import_class("models.association.apriori", "AprioriTrainer"))
    fpgrowth_trainer = providers.Factory(lambda: _import_class("models.association.fpgrowth", "FPGrowthTrainer"))
    eclat_trainer = providers.Factory(lambda: _import_class("models.association.eclat", "EclatTrainer"))

    # ========== Model Registry ==========
    model_registry = providers.Singleton(
        lambda kmeans, dbscan, agglomerative, linear_regression, ridge_regression,
               random_forest_regressor, lgbm_regressor, logistic_regression,
               random_forest_classifier, svm_classifier, lgbm_classifier,
               pca, tsne, arima, sarimax, dowhy, causal_impact, apriori, fpgrowth, eclat:
        _create_model_registry_from_di(
            kmeans, dbscan, agglomerative, linear_regression, ridge_regression,
            random_forest_regressor, lgbm_regressor, logistic_regression,
            random_forest_classifier, svm_classifier, lgbm_classifier,
            pca, tsne, arima, sarimax, dowhy, causal_impact, apriori, fpgrowth, eclat
        ),
        kmeans=kmeans_trainer,
        dbscan=dbscan_trainer,
        agglomerative=agglomerative_trainer,
        linear_regression=linear_regression_trainer,
        ridge_regression=ridge_regression_trainer,
        random_forest_regressor=random_forest_regressor_trainer,
        lgbm_regressor=lgbm_regressor_trainer,
        logistic_regression=logistic_regression_trainer,
        random_forest_classifier=random_forest_classifier_trainer,
        svm_classifier=svm_classifier_trainer,
        lgbm_classifier=lgbm_classifier_trainer,
        pca=pca_trainer,
        tsne=tsne_trainer,
        arima=arima_trainer,
        sarimax=sarimax_trainer,
        dowhy=dowhy_trainer,
        causal_impact=causal_impact_trainer,
        apriori=apriori_trainer,
        fpgrowth=fpgrowth_trainer,
        eclat=eclat_trainer,
    )

    # ========== Storage ==========
    file_storage = providers.Singleton(
        lambda cfg: _create_file_storage(cfg),
        cfg=config,
    )

    db_storage = providers.Singleton(
        lambda pool: _create_db_storage(pool),
        pool=db_pool,
    )

    # ========== Services ==========
    training_service = providers.Singleton(
        lambda registry, file_storage, db_storage: _create_training_service(
            registry, file_storage, db_storage
        ),
        registry=model_registry,
        file_storage=file_storage,
        db_storage=db_storage,
    )

    prediction_service = providers.Singleton(
        lambda db_storage: _create_prediction_service(db_storage),
        db_storage=db_storage,
    )

    stability_selection_service = providers.Singleton(
        lambda db_storage: _create_stability_selection_service(db_storage),
        db_storage=db_storage,
    )

    shap_curve_service = providers.Singleton(
        lambda db_storage: _create_shap_curve_service(db_storage),
        db_storage=db_storage,
    )

    # ========== Event Publishing ==========
    event_publisher = providers.Singleton(
        lambda cfg: _create_event_publisher(cfg),
        cfg=config,
    )

    # ========== Pipeline Nodes ==========
    training_node = providers.Singleton(
        lambda cfg, training_service, pool, query_throttler, event_publisher, async_throttler: _create_training_node(
            cfg, training_service, pool, query_throttler, event_publisher, async_throttler
        ),
        cfg=config,
        training_service=training_service,
        pool=db_pool,
        query_throttler=query_throttler,
        event_publisher=event_publisher,
        async_throttler=async_throttler,
    )

    tuning_node = providers.Singleton(
        lambda cfg, training_service, pool, query_throttler, event_publisher, async_throttler: _create_tuning_node(
            cfg, training_service, pool, query_throttler, event_publisher, async_throttler
        ),
        cfg=config,
        training_service=training_service,
        pool=db_pool,
        query_throttler=query_throttler,
        event_publisher=event_publisher,
        async_throttler=async_throttler,
    )

    stability_selection_node = providers.Singleton(
        lambda cfg, stability_selection_service, pool, query_throttler, event_publisher, async_throttler:
        _create_stability_selection_node(
            cfg, stability_selection_service, pool, query_throttler, event_publisher, async_throttler
        ),
        cfg=config,
        stability_selection_service=stability_selection_service,
        pool=db_pool,
        query_throttler=query_throttler,
        event_publisher=event_publisher,
        async_throttler=async_throttler,
    )

    shap_node = providers.Singleton(
        lambda cfg, shap_curve_service, event_publisher:
        _create_shap_node(cfg, shap_curve_service, event_publisher),
        cfg=config,
        shap_curve_service=shap_curve_service,
        event_publisher=event_publisher,
    )


# Keep old Container name for backward compatibility
Container = ApplicationContainer


def _create_db_pool(cfg: Settings):
    """Create database connection pool."""
    from datasource.sql_datasource import create_connection_pool
    return create_connection_pool(cfg.database)


def _create_throttler(cfg: Settings):
    """Create query throttler."""
    from datasource.throttler import QueryThrottler
    return QueryThrottler(
        max_concurrent=cfg.datasource.max_concurrent_queries,
        wait_timeout=cfg.datasource.throttle_wait_seconds,
    )


def _create_async_throttler(throttler):
    """Create async throttler wrapper."""
    from datasource.throttler import AsyncThrottlerWrapper
    return AsyncThrottlerWrapper(throttler)


def _create_datasource(cfg: Settings, pool, throttler):
    """Create SQL datasource."""
    from datasource.sql_datasource import PostgreSQLDatasource
    return PostgreSQLDatasource(
        pool=pool,
        throttler=throttler,
        query_timeout=cfg.datasource.query_timeout_seconds,
        explain_timeout=cfg.datasource.explain_analyze_timeout_seconds,
        lazy_threshold_bytes=cfg.datasource.lazy_materialization_threshold_bytes,
        absolute_max_bytes=cfg.datasource.absolute_max_bytes,
    )


def _import_class(module_path: str, class_name: str):
    """Import a class from a module."""
    import importlib
    module = importlib.import_module(module_path, __package__)
    return getattr(module, class_name)


def _create_model_registry_from_di(
        kmeans,
        dbscan,
        agglomerative,
        linear_regression,
        ridge_regression,
        random_forest_regressor,
        lgbm_regressor,
        logistic_regression,
        random_forest_classifier,
        svm_classifier,
        lgbm_classifier,
        pca,
        tsne,
        arima,
        sarimax,
        dowhy,
        causal_impact,
        apriori,
        fpgrowth,
        eclat,
):
    """Create model registry and populate with injected trainer providers."""
    from models.registry import ModelTrainerRegistry

    registry = ModelTrainerRegistry()

    # Map provider names to model types
    trainer_providers = {
        "kmeans": kmeans,
        "dbscan": dbscan,
        "agglomerative": agglomerative,
        "linear_regression": linear_regression,
        "ridge_regression": ridge_regression,
        "random_forest_regressor": random_forest_regressor,
        "lgbm_regressor": lgbm_regressor,
        "logistic_regression": logistic_regression,
        "random_forest_classifier": random_forest_classifier,
        "svm_classifier": svm_classifier,
        "lgbm_classifier": lgbm_classifier,
        "pca": pca,
        "tsne": tsne,
        "arima": arima,
        "sarimax": sarimax,
        "dowhy": dowhy,
        "causal_impact": causal_impact,
        "apriori": apriori,
        "fpgrowth": fpgrowth,
        "eclat": eclat,
    }

    # Register factories that delegate to injected providers
    for model_type, provider in trainer_providers.items():
        registry.register_factory(model_type, lambda p=provider: p())

    return registry


def _create_file_storage(cfg: Settings):
    """Create file-based model storage."""
    from storage.file_storage import FileModelStorage
    return FileModelStorage(
        base_directory=cfg.storage.models_directory,
        create_if_missing=cfg.storage.create_if_missing,
    )


def _create_db_storage(pool):
    """Create database model storage."""
    from storage.db_storage import DatabaseModelStorage
    return DatabaseModelStorage(pool)


def _create_training_service(registry, file_storage, db_storage):
    """Create training service."""
    from service.training_service import TrainingService
    service = TrainingService(registry=registry, storage=file_storage)
    service._db_storage = db_storage
    return service


def _create_prediction_service(db_storage):
    """Create prediction service."""
    from service.prediction_service import PredictionService
    return PredictionService(db_storage)


def _create_stability_selection_service(db_storage):
    """Create stability selection service."""
    from service.stability_selection_service import StabilitySelectionService
    return StabilitySelectionService(db_storage=db_storage)


def _create_shap_curve_service(db_storage):
    """Create SHAP curve service."""
    from service.shap_curve_service import ShapCurveService
    return ShapCurveService(db_storage=db_storage)


def _create_event_publisher(cfg: Settings):
    """Create event publisher."""
    from events.publisher import EventPublisher
    return EventPublisher(redis_url=cfg.redis.url)


def _create_training_node(cfg: Settings, training_service, pool, query_throttler, event_publisher, async_throttler):
    """Create training pipeline node."""
    from processing.training_node import TrainingPipelineNode
    from datasource.sql_datasource import PostgreSQLDatasource

    def datasource_factory():
        """Factory function to create new datasource instances."""
        return PostgreSQLDatasource(
            pool=pool,
            throttler=query_throttler,
            query_timeout=cfg.datasource.query_timeout_seconds,
            explain_timeout=cfg.datasource.explain_analyze_timeout_seconds,
            lazy_threshold_bytes=cfg.datasource.lazy_materialization_threshold_bytes,
            absolute_max_bytes=cfg.datasource.absolute_max_bytes,
        )

    return TrainingPipelineNode(
        redis_url=cfg.redis.url,
        input_stream=cfg.pipeline.streams.training_requests,
        output_stream=cfg.pipeline.streams.training_results,
        training_service=training_service,
        datasource_factory=datasource_factory,
        event_publisher=event_publisher,
        throttler=async_throttler,
        consumer_group=cfg.pipeline.consumer_groups.training,
    )


def _create_tuning_node(cfg: Settings, training_service, pool, query_throttler, event_publisher, async_throttler):
    """Create hyperparameter tuning pipeline node."""
    from processing.tuning_node import HyperparameterTuningNode
    from datasource.sql_datasource import PostgreSQLDatasource

    def datasource_factory():
        """Factory function to create new datasource instances."""
        return PostgreSQLDatasource(
            pool=pool,
            throttler=query_throttler,
            query_timeout=cfg.datasource.query_timeout_seconds,
            explain_timeout=cfg.datasource.explain_analyze_timeout_seconds,
            lazy_threshold_bytes=cfg.datasource.lazy_materialization_threshold_bytes,
            absolute_max_bytes=cfg.datasource.absolute_max_bytes,
        )

    return HyperparameterTuningNode(
        redis_url=cfg.redis.url,
        input_stream=cfg.pipeline.streams.tuning_requests,
        output_stream=cfg.pipeline.streams.training_requests,
        training_service=training_service,
        datasource_factory=datasource_factory,
        event_publisher=event_publisher,
        throttler=async_throttler,
        system_max_tuning_time=cfg.tuning.max_tuning_time_seconds,
        consumer_group=cfg.pipeline.consumer_groups.tuning,
    )


def _create_stability_selection_node(
        cfg: Settings,
        stability_selection_service,
        pool,
        query_throttler,
        event_publisher,
        async_throttler,
):
    """Create async stability selection pipeline node."""
    from processing.stability_selection_node import StabilitySelectionPipelineNode
    from datasource.sql_datasource import PostgreSQLDatasource

    def datasource_factory():
        """Factory function to create new datasource instances."""
        return PostgreSQLDatasource(
            pool=pool,
            throttler=query_throttler,
            query_timeout=cfg.datasource.query_timeout_seconds,
            explain_timeout=cfg.datasource.explain_analyze_timeout_seconds,
            lazy_threshold_bytes=cfg.datasource.lazy_materialization_threshold_bytes,
            absolute_max_bytes=cfg.datasource.absolute_max_bytes,
        )

    return StabilitySelectionPipelineNode(
        redis_url=cfg.redis.url,
        input_stream=cfg.pipeline.streams.stability_selection_requests,
        stability_selection_service=stability_selection_service,
        datasource_factory=datasource_factory,
        event_publisher=event_publisher,
        throttler=async_throttler,
        consumer_group=cfg.pipeline.consumer_groups.stability_selection,
    )


def _create_shap_node(cfg: Settings, shap_curve_service, event_publisher):
    """Create async SHAP curve computation pipeline node."""
    from processing.shap_node import ShapPipelineNode

    return ShapPipelineNode(
        redis_url=cfg.redis.url,
        input_stream=cfg.pipeline.streams.shap_requests,
        shap_curve_service=shap_curve_service,
        event_publisher=event_publisher,
        consumer_group=cfg.pipeline.consumer_groups.shap,
    )
