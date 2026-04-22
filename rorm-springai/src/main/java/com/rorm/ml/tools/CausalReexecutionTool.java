package com.rorm.ml.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rorm.ai.DeferredToolResult;
import com.rorm.ai.RormToolContext;
import com.rorm.ml.MlTrainingService;
import com.rorm.ml.dto.AsyncJobResponse;
import com.rorm.ml.dto.PipelineSpecPatch;
import com.rorm.ml.exception.MlServiceException;
import com.rorm.ml.stream.JobCompletionHandler;
import com.rorm.ml.stream.JobEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class CausalReexecutionTool {

    private final MlTrainingService mlService;
    private final ObjectMapper objectMapper;
    private final JobCompletionHandler completionHandler;

    @Tool(
        name = "reexecuteCausalPipeline",
        description = """
            Re-execute a completed causal verification run with a PARTIAL spec change.
            
            Only the pipeline steps affected by the changed fields are re-computed;
            unaffected steps are loaded from the base run's checkpoints.
            Returns per-variable diffs between the old and new results.
            
            The dependency DAG determines which steps are invalidated:
            - datasource, strip_columns → load_data → cascades to most downstream steps
            - dag_edges, dsep_threshold → dsep → estimation, refutations, sensitivity, residual_diagnostics
            - adjustment_set, mediators_excluded → identification → gates, mediation, grf, range_checks, residual_diagnostics
            - estimation_variants → estimation → gates, mediation, refutations, sensitivity, structural_breaks, ...
            - gates → gates, refutations
            - sensitivity → sensitivity
            - grf_configs → grf → externalization
            - refutations → refutations
            - range_checks → range_checks
            - residual_checks → residual_diagnostics
            - externalization → externalization
            
            The new run is frozen and can itself be used as a base for further re-executions.
            """
    )
    public String reexecuteCausalPipeline(

        @ToolParam(description = """
            Run ID of the base run to re-execute against. Optional — when
            omitted, the current pipeline run from context is used (this is
            the normal mode inside compiler-sceptic review). Provide
            explicitly only when re-executing a different run then initial you were provided with.
            
            This is NOT hypothesis id, it is run id of the pipeline result,
            and you should not specify it for initial rerun - it will be implicitly
            resolved from context
            """)
        @Nullable String runId,

        @ToolParam(description = """
            Partial spec patch — include ONLY the fields you want to change.
            Omitted fields keep the base run's values. Nested objects are
            deep-merged (e.g. providing only gates.sanity changes sanity
            while keeping nuisance_r2 and placebo). Lists are replaced
            wholesale.""")
        PipelineSpecPatch specPatch,

        ToolContext toolContext

    ) {
        var resolvedRunId = resolveRunId(runId, toolContext);
        var ctx = RormToolContext.from(toolContext);
        var journal = ctx.stepJournal();
        var callId = ctx.id();
        try {
            log.info("Submitting causal pipeline re-execution for run '{}'", resolvedRunId);

            var response = journal.run(
                callId + ":submit",
                AsyncJobResponse.class,
                () -> mlService.reexecutePipeline(resolvedRunId, specPatch)
            );

            if (response.isNotAccepted()) {
                return errorResponse("Re-execution not accepted: " + response.message());
            }

            log.info("Re-execution queued: analysis_id={}", response.analysisId());

            var future = journal.awakeable(JobEvent.class);
            completionHandler.register(response.analysisId(), future);
            return DeferredToolResult.defer(toolContext, future.map(this::writeJson));
        } catch (MlServiceException e) {
            log.error("Pipeline re-execution failed for run '{}'", resolvedRunId, e);
            return errorResponse("Re-execution failed: " + e.getMessage());
        }
    }

    private static String resolveRunId(@Nullable String explicit, ToolContext toolContext) {
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        var fromContext = toolContext.getContext().get("pipelineRunId");
        if (fromContext instanceof String s && !s.isBlank()) {
            return s;
        }
        throw new IllegalArgumentException(
            "runId is required — provide it explicitly or ensure pipelineRunId is in the tool context");
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

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return errorResponse(e.getMessage());
        }
    }

    @Tool(
        name = "listCompletedCausalRuns",
        description = """
            List completed causal verification runs available for re-execution.
            Returns run metadata (run_id, status, parent_run_id, changed_fields)
            without the full result or spec to keep the response compact.
            Use this to find a base run_id for reexecuteCausalPipeline."""
    )
    public String listCompletedCausalRuns(

        @ToolParam(description = "Maximum number of runs to return (1–500, default 20).")
        @Nullable Integer limit

    ) {
        try {
            var runs = mlService.listCausalRuns(limit != null ? limit : 20);
            return writeJson(Map.of("runs", runs, "count", runs.size()));
        } catch (MlServiceException e) {
            log.error("Failed to list causal runs", e);
            return errorResponse("Failed to list runs: " + e.getMessage());
        }
    }

    @Tool(
        name = "getCausalRunDetails",
        description = """
            Get full metadata and result for a specific causal verification run.
            Includes the frozen spec, lineage (parent_run_id), and the complete
            pipeline result. Use this to inspect a run before deciding what to
            change in a re-execution."""
    )
    public String getCausalRunDetails(

        @ToolParam(description = "The run_id to inspect.")
        String runId

    ) {
        try {
            var run = mlService.getCausalRun(runId);
            return writeJson(run);
        } catch (MlServiceException e) {
            log.error("Failed to get causal run '{}'", runId, e);
            return errorResponse("Failed to get run: " + e.getMessage());
        }
    }
}
