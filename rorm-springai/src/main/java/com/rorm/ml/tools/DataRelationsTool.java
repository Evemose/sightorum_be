package com.rorm.ml.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rorm.ai.DeferredToolResult;
import com.rorm.ai.RormToolContext;
import com.rorm.dto.dense.DenseQueryDto;
import com.rorm.engine.QueryTransformer;
import com.rorm.mapper.DenseQueryMapper;
import com.rorm.ml.MlTrainingService;
import com.rorm.ml.dto.DatasourceConfig;
import com.rorm.ml.dto.ShapJobRequest;
import com.rorm.ml.dto.StabilitySelectionJobRequest;
import com.rorm.ml.exception.MlServiceException;
import com.rorm.ml.stream.JobCompletionHandler;
import com.rorm.ml.stream.JobEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.conf.ParamType;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class DataRelationsTool {

    private final MlTrainingService mlService;
    private final JobCompletionHandler completionHandler;
    private final ObjectMapper objectMapper;
    private final DenseQueryMapper denseQueryMapper;
    private final QueryTransformer queryTransformer;

    @Tool(
        name = "discoverDataRelations",
        description = """
            EXPENSIVE: Discover which features genuinely drive a target variable using
            stability selection — a statistically robust feature importance method.
            
            HOW IT WORKS:
            Runs repeated bootstrap subsampling across multiple model families (LightGBM, elastic net, linear) \
            and measures how consistently each feature appears as important.
            Features that appear important across many resamples
            and model types are truly predictive, not just correlated by chance.
            
            RETURNS:
            - Per-feature stability scores (0-1, higher = more reliably important)
            - Consensus ranking across model families
            - Polynomial interaction detection (degree 2 by default)
            - Correlated feature groups (features above correlation_threshold are grouped)
            
            WHEN NOT TO USE:
            - When you already know the features (just train directly)
            - For very small datasets (<100 rows) — bootstrap resampling needs volume
            
            AFTER CALLING THIS TOOL:
            - Analysis runs in the background via the ML service
            - Results include a run_id that can be used with getShapCurves for deeper analysis
            - Continue with other analysis while waiting
            """
    )
    public String discoverDataRelations(

        @ToolParam(description = """
            Brief explanation of WHAT RELATIONSHIPS you're trying to discover.
            Focus on the analytical question, not the technique.
            
            Good: "Identify which customer attributes most reliably predict churn"
            Bad: "Run stability selection"
            """)
        String reason,

        @ToolParam(description = """
            Query that returns the data to analyze. Use the same QueryDTO schema as executeQuery.
            Should select the target column and all candidate feature columns.
            """)
        DenseQueryDto dataQuery,

        @ToolParam(description = """
            The outcome column to analyze. Which features drive THIS column?
            Required — this is the variable whose drivers you want to discover.
            """)
        String targetColumn,

        @ToolParam(description = """
            Specific columns to evaluate as potential drivers. If null/empty,
            all columns except target are analyzed.
            """)
        @Nullable List<String> featureColumns,

        @ToolParam(description = """
            Columns to control for via residualisation (Frisch-Waugh-Lovell).
            Their effects are partialled out from both features and target before analysis,
            so that stability selection discovers features important ABOVE AND BEYOND
            these controls. If null, no residualisation is applied.
            """)
        @Nullable List<String> controlFeatures,

        @ToolParam(description = """
            Problem type: "regression" for numeric targets, "classification" for categorical.
            If null, auto-detected from the target column.
            """)
        @Nullable String problemType,

        @ToolParam(description = """
            Number of bootstrap resampling rounds. More rounds = more statistical confidence
            but longer runtime. Minimum 50, default 50. Use 100+ for publication-grade results.
            """)
        @Nullable Integer bootstrapRuns,

        ToolContext toolContext
    ) {
        try {
            log.info("Launching stability selection for target '{}'", targetColumn);

            var context = RormToolContext.from(toolContext);
            var query = denseQueryMapper.toEntity(dataQuery, context.modelSpace());
            var jooqQuery = queryTransformer.transform(query, context.schema());

            var request = StabilitySelectionJobRequest.builder()
                .reason(reason)
                .datasource(new DatasourceConfig(jooqQuery.getSQL(ParamType.INLINED), Map.of()))
                .targetColumn(targetColumn)
                .featureColumns(featureColumns)
                .controlFeatures(controlFeatures)
                .problemType(problemType)
                .bootstrapRuns(bootstrapRuns != null ? bootstrapRuns : 50)
                .sampleFraction(0.8)
                .correlationThreshold(0.8)
                .randomState(42)
                .build();

            var journal = context.stepJournal();
            var jobId = journal.run(context.id() + ":submit", UUID.class, () -> {
                var resp = mlService.submitStabilitySelection(request);
                if (resp.isNotAccepted()) {
                    throw new MlServiceException("Stability selection not accepted: " + resp.message());
                }
                return resp.analysisId();
            });
            var future = journal.awakeable(JobEvent.class);
            completionHandler.register(jobId, future);
            return DeferredToolResult.defer(toolContext,
                future.map(this::writeJson));

        } catch (Exception e) {
            log.error("Failed to launch stability selection", e);
            return errorResponse("Failed to launch data relations discovery: " + e.getMessage());
        }
    }


    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return errorResponse(e.getMessage());
        }
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
        name = "getShapCurves",
        description = """
            EXPENSIVE: Compute SHAP dependence curves for a completed stability selection run.
            
            SHAP curves reveal HOW each feature affects the target:
            - For numeric features: curve showing effect at each value (reveals thresholds, non-linearities)
            - For categorical features: per-category impact magnitude
            - Breakpoint detection: identifies threshold values where the relationship changes
            
            REQUIRES: A completed stability selection run_id from discoverDataRelations.
            
            USE THIS to go from "feature X is important" to "feature X matters because
            values above 42 dramatically increase the target, with a threshold at 42".
            
            AFTER CALLING THIS TOOL:
            - SHAP computation runs in the background via the ML service
            - Results will be automatically available after the current step completes
            - Continue with other analysis while waiting
            """
    )
    public String getShapCurves(

        @ToolParam(description = """
            Brief explanation of WHY you need SHAP curves and what you expect to learn.
            Focus on the analytical question.
            
            Good: "Understand how customer age affects churn probability — looking for threshold effects"
            Bad: "Get SHAP curves"
            """)
        String reason,

        @ToolParam(description = "Run ID from a completed stability selection analysis")
        String runId,

        @ToolParam(description = """
            Specific features to compute curves for. If null, computes for all features
            from the stability selection run. Use this to focus on the top important features.
            """)
        @Nullable List<String> features,

        @ToolParam(description = "Number of bins for numeric feature curves. Default 100. Lower = smoother curves.")
        @Nullable Integer nBins,

        ToolContext toolContext

    ) {
        try {
            log.info("Launching async SHAP curves for run '{}'", runId);

            var request = new ShapJobRequest(
                reason,
                runId,
                features,
                nBins != null ? nBins : 100,
                1
            );

            var ctx = RormToolContext.from(toolContext);
            var journal = ctx.stepJournal();
            var callId = ctx.id();
            var jobId = journal.run(callId + ":submit", UUID.class, () -> {
                var resp = mlService.submitShapCurvesAsync(request);
                if (resp.isNotAccepted()) {
                    throw new MlServiceException("SHAP not accepted: " + resp.message());
                }
                return resp.analysisId();
            });
            var future = journal.awakeable(JobEvent.class);
            completionHandler.register(jobId, future);
            return DeferredToolResult.defer(toolContext,
                future.map(this::writeJson));

        } catch (Exception e) {
            log.error("Failed to launch SHAP curves", e);
            return errorResponse("Failed to launch SHAP curves: " + e.getMessage());
        }
    }
}
