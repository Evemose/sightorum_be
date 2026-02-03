package com.rorm.ml.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rorm.ai.RormToolContext;
import com.rorm.ai.chat.ChatForkService;
import com.rorm.dto.QueryDTO;
import com.rorm.engine.QueryTransformer;
import com.rorm.mapper.QueryMapper;
import com.rorm.ml.MlTrainingService;
import com.rorm.ml.dto.*;
import com.rorm.ml.dto.model.train.ModelConfig;
import com.rorm.ml.dto.model.tune.TuningModelConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RequiredArgsConstructor
public class MlTrainingTool {

    private final MlTrainingService trainingService;
    private final ChatForkService chatForkService;
    private final ObjectMapper objectMapper;
    private final QueryMapper queryMapper;
    private final QueryTransformer queryTransformer;

    @Tool(
        name = "launchModelTraining",
        description = """
            ASYNC & EXPENSIVE: Launch an ML model training job.
            
            CRITICAL CONSIDERATIONS:
            - This operation is ASYNCHRONOUS - training runs in background and takes significant time
            - This operation is EXPENSIVE - consumes substantial compute resources
            - Use ONLY when there is substantial evidence that ML training is necessary
            - This session will NOT receive training results - a separate forked session handles completion
            
            AFTER CALLING THIS TOOL:
            - The current conversation is forked; a background session will process training results
            - You should either:
              a) YIELD CONTROL: Inform the user that training has started and they will be notified upon completion
              b) CONTINUE RESEARCH: Proceed with other analysis/research unrelated to the pending training
            - Do NOT wait for or expect training results in this session
            
            PREREQUISITES:
            - Use the executeQuery tool first to verify the query returns expected data
            - Ensure target column and feature columns are valid for the model type
            - Consider if simpler analysis would suffice before training
            """
    )
    public String launchModelTraining(
        @ToolParam(description = """
            Model configuration with type and hyperparameters. The modelType field determines the model.
            
            CLASSIFICATION models (require targetColumn):
            - random_forest_classifier: Ensemble of decision trees
            - logistic_regression: Linear model for binary/multiclass
            - svm_classifier: Support vector machine
            - lgbm_classifier: LightGBM gradient boosting
            
            REGRESSION models (require targetColumn):
            - linear_regression: Ordinary least squares
            - ridge_regression: Linear with L2 regularization
            - random_forest_regressor: Ensemble for regression
            - lgbm_regressor: LightGBM for regression
            
            CLUSTERING models (no targetColumn):
            - kmeans: K-Means clustering
            - dbscan: Density-based clustering
            - agglomerative: Hierarchical clustering
            
            DIMENSIONALITY REDUCTION (no targetColumn):
            - pca: Principal Component Analysis
            - tsne: t-SNE for visualization
            
            TIME SERIES (require targetColumn as time series values):
            - arima: ARIMA forecasting
            - sarimax: Seasonal ARIMA with exogenous variables
            """)
        ModelConfig modelConfig,

        @ToolParam(description = "Human-readable name for the trained model (e.g., 'customer_churn_predictor')")
        String modelName,

        @ToolParam(description = """
            Query that returns training data. Use the same QueryDTO schema as executeQuery tool.
            The query should select:
            - The target column (for supervised learning)
            - Feature columns for the model
            """)
        QueryDTO dataQuery,

        @ToolParam(description = """
            Name of the column to predict (target variable).
            REQUIRED for: classification, regression, and time series models.
            OMIT for: clustering and dimensionality reduction models.
            """)
        String targetColumn,

        @ToolParam(description = """
            List of column names to use as features.
            If null/empty, all columns except target are used as features.
            """)
        List<String> featureColumns,

        ToolContext toolContext
    ) {
        try {
            log.info("Launching training for model type '{}' named '{}'", modelConfig.modelType(), modelName);

            // Extract context
            var context = RormToolContext.from(toolContext);
            var currentProgress = context.chatProgress();

            var query = queryMapper.toEntity(dataQuery, currentProgress.getModelSpace());
            var jooqQuery = queryTransformer.transform(query);
            var sql = jooqQuery.getSQL();
            var bindValues = extractBindVariables(jooqQuery);

            var request = TrainingJobRequest.builder()
                .datasource(new DatasourceConfig(sql, bindValues))
                .targetColumn(targetColumn)
                .featureColumns(featureColumns)
                .modelConfig(modelConfig)
                .build();

            var response = trainingService.submitTraining(request);

            if (!response.isAccepted()) {
                return errorResponse("Training job was not accepted: " + response.message());
            }

            var forkedProgress = chatForkService.forkForTraining(currentProgress, request, response);

            var result = new TrainingLaunchResult(
                true,
                response.trainingId(),
                forkedProgress.getConversationId(),
                response.message(),
                """
                    Training job launched successfully.
                    - Training ID: %s
                    - Model Type: %s
                    - Forked conversation: %s
                    
                    IMPORTANT: This session will NOT receive training results.
                    The forked session will be notified when training completes.
                    You should now either yield control or continue with other research.
                    """.formatted(response.trainingId(), modelConfig.modelType(), forkedProgress.getConversationId()),
                null
            );

            return objectMapper.writeValueAsString(result);

        } catch (Exception e) {
            log.error("Failed to launch training", e);
            return errorResponse("Failed to launch training: " + e.getMessage());
        }
    }

    private Map<String, Object> extractBindVariables(org.jooq.Query jooqQuery) {
        var bindValues = jooqQuery.getBindValues();
        if (bindValues.isEmpty()) {
            return Map.of();
        }
        var result = new LinkedHashMap<String, Object>();
        int i = 1;
        for (var value : bindValues) {
            result.put("p" + i++, value);
        }
        return result;
    }

    private String errorResponse(String message) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                "success", false,
                "error", message
            ));
        } catch (JsonProcessingException e) {
            return "{\"success\":false,\"error\":\"" + message.replace("\"", "\\\"") + "\"}";
        }
    }

    @Tool(
        name = "listAvailableModelTypes",
        description = """
            List all available ML model types that can be trained.
            Returns model types grouped by category (classification, regression, clustering, etc.)
            with their supported parameters and use cases.
            
            Call this before launchModelTraining if you're unsure which model type to use.
            """
    )
    public String listAvailableModelTypes() {
        try {
            var modelTypes = trainingService.listAvailableModelTypes();
            return objectMapper.writeValueAsString(new ModelTypesResult(true, modelTypes, null));
        } catch (Exception e) {
            log.error("Failed to list model types", e);
            return errorResponse("Failed to list model types: " + e.getMessage());
        }
    }

    @Tool(
        name = "listTrainedModels",
        description = """
            List all previously trained models available for predictions.
            Returns model UUIDs, names, types, and training metadata.
            
            Use this to find existing models before deciding whether to train a new one.
            """
    )
    public String listTrainedModels() {
        try {
            var models = trainingService.listModels();
            return objectMapper.writeValueAsString(new TrainedModelsResult(true, models, null));
        } catch (Exception e) {
            log.error("Failed to list trained models", e);
            return errorResponse("Failed to list trained models: " + e.getMessage());
        }
    }

    @Tool(
        name = "getModelInfo",
        description = """
            Get detailed information about a specific trained model.
            Returns model metadata, training metrics, feature importance, and configuration.
            """
    )
    public String getModelInfo(
        @ToolParam(description = "UUID of the trained model to inspect")
        UUID modelUuid
    ) {
        try {
            var modelInfo = trainingService.getModelInfo(modelUuid);
            if (modelInfo.isEmpty()) {
                return errorResponse("Model not found: " + modelUuid);
            }
            return objectMapper.writeValueAsString(new ModelInfoResult(true, modelInfo.get(), null));
        } catch (Exception e) {
            log.error("Failed to get model info", e);
            return errorResponse("Failed to get model info: " + e.getMessage());
        }
    }

    @Tool(
        name = "tuneAndTrainModel",
        description = """
            ASYNC & VERY EXPENSIVE: Tune hyperparameters then train ML model.
            
            CRITICAL CONSIDERATIONS:
            - This operation is ASYNCHRONOUS - tuning + training runs in background
            - This operation is VERY EXPENSIVE - runs multiple training trials for optimization
            - Use ONLY when hyperparameter optimization is critical for model performance
            - This session will NOT receive results - a separate forked session handles completion
            
            WORKFLOW:
            1. Hyperparameter tuning with Optuna (multiple trials)
            2. Training final model with best parameters
            3. Results delivered to forked session
            
            AFTER CALLING THIS TOOL:
            - The current conversation is forked; a background session will process results
            - You should either:
              a) YIELD CONTROL: Inform the user that tuning has started
              b) CONTINUE RESEARCH: Proceed with other analysis unrelated to pending tuning
            - Do NOT wait for or expect tuning/training results in this session
            
            PREREQUISITES:
            - Use the executeQuery tool first to verify the query returns expected data
            - Ensure target column and feature columns are valid for the model type
            - Consider if regular training (without tuning) would suffice
            """
    )
    public String tuneAndTrainModel(
        @ToolParam(description = """
            Tuning configuration with parameter search spaces. The modelType field determines the model.
            
            Each parameter must be a search space:
            - IntSpace: {"type": "int", "low": 10, "high": 100, "log": false}
            - FloatSpace: {"type": "float", "low": 0.01, "high": 1.0, "log": true}
            - CategoricalSpace: {"type": "categorical", "choices": ["auto", "sqrt", "log2"]}
            
            See launchModelTraining for available model types and their parameters.
            """)
        TuningModelConfig tuningConfig,

        @ToolParam(description = "Human-readable name for the trained model (e.g., 'optimized_churn_predictor')")
        String modelName,

        @ToolParam(description = """
            Query that returns training data. Use the same QueryDTO schema as executeQuery tool.
            The query should select:
            - The target column (for supervised learning)
            - Feature columns for the model
            """)
        QueryDTO dataQuery,

        @ToolParam(description = """
            Name of the column to predict (target variable).
            REQUIRED for: classification, regression, and time series models.
            OMIT for: clustering and dimensionality reduction models.
            """)
        String targetColumn,

        @ToolParam(description = """
            List of column names to use as features.
            If null/empty, all columns except target are used as features.
            """)
        List<String> featureColumns,

        @ToolParam(description = """
            Tuning configuration controlling the optimization process:
            - nTrials: Number of Optuna trials (default: 50)
            - maxTuningTime: Maximum tuning time in seconds (default: 300)
            - metric: Metric to optimize - "auto" or specific metric name (default: "auto")
            """)
        TuningConfig tuningSettings,

        ToolContext toolContext
    ) {
        try {
            log.info("Launching hyperparameter tuning for model type '{}' named '{}'",
                tuningConfig.modelType(), modelName);

            // Extract context
            var context = RormToolContext.from(toolContext);
            var currentProgress = context.chatProgress();

            var query = queryMapper.toEntity(dataQuery, currentProgress.getModelSpace());
            var jooqQuery = queryTransformer.transform(query);
            var sql = jooqQuery.getSQL();
            var bindValues = extractBindVariables(jooqQuery);

            var baseRequest = TuningJobRequest.BaseTrainingRequest.builder()
                .modelType(tuningConfig.modelType())
                .modelName(modelName)
                .datasource(new DatasourceConfig(sql, bindValues))
                .targetColumn(targetColumn)
                .featureColumns(featureColumns)
                .modelParams(Map.of())
                .build();

            var request = TuningJobRequest.builder()
                .request(baseRequest)
                .paramSpace(tuningConfig)
                .tuningConfig(tuningSettings != null ? tuningSettings : TuningConfig.defaults())
                .build();

            var response = trainingService.submitTuningThenTraining(request);

            if (!response.isAccepted()) {
                return errorResponse("Tuning job was not accepted: " + response.message());
            }

            var forkedProgress = chatForkService.forkForTraining(currentProgress, request, response);

            var result = new TuningLaunchResult(
                true,
                response.trainingId(),
                forkedProgress.getConversationId(),
                response.message(),
                """
                    Hyperparameter tuning job launched successfully.
                    - Training ID: %s
                    - Model Type: %s
                    - Tuning Trials: %d
                    - Forked conversation: %s
                    
                    IMPORTANT: This session will NOT receive tuning/training results.
                    The forked session will be notified when optimization completes.
                    You should now either yield control or continue with other research.
                    """.formatted(
                    response.trainingId(),
                    tuningConfig.modelType(),
                    tuningSettings != null ? tuningSettings.nTrials() : 50,
                    forkedProgress.getConversationId()
                ),
                null
            );

            return objectMapper.writeValueAsString(result);

        } catch (Exception e) {
            log.error("Failed to launch hyperparameter tuning", e);
            return errorResponse("Failed to launch tuning: " + e.getMessage());
        }
    }

    public record TrainingLaunchResult(
        boolean success,
        UUID trainingId,
        UUID forkedConversationId,
        String status,
        String instructions,
        String error
    ) {}

    public record TuningLaunchResult(
        boolean success,
        UUID trainingId,
        UUID forkedConversationId,
        String status,
        String instructions,
        String error
    ) {}

    public record ModelTypesResult(
        boolean success,
        List<Map<String, Object>> modelTypes,
        String error
    ) {}

    public record TrainedModelsResult(
        boolean success,
        List<ModelInfo> models,
        String error
    ) {}

    public record ModelInfoResult(
        boolean success,
        ModelInfo model,
        String error
    ) {}
}
