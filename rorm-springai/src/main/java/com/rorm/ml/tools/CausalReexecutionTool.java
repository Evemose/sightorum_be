package com.rorm.ml.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rorm.ai.JournaledTool;
import com.rorm.ml.MlTrainingService;
import com.rorm.ml.exception.MlServiceException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
@JournaledTool
@RequiredArgsConstructor
public class CausalReexecutionTool {

    private final MlTrainingService mlService;
    private final ObjectMapper objectMapper;

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
            
            USE THIS when you want to test "what if we changed X?" without re-running the
            entire 14-step pipeline from scratch.
            
            The new run is frozen and can itself be used as a base for further re-executions.
            """
    )
    public String reexecuteCausalPipeline(

        @ToolParam(description = """
            The run_id of a completed causal verification run to use as the base.
            Use listCompletedCausalRuns to discover available runs.""")
        String runId,

        @ToolParam(description = """
            Partial spec patch — only include the fields you want to change.
            Uses the same field names as the PipelineSpec (snake_case):
            adjustment_set, estimation_variants, gates, sensitivity,
            grf_configs, refutations, mediation, range_checks,
            residual_checks, unmeasured_confounding, externalization,
            dag_edges, dsep_threshold, structural_breaks, strip_columns.
            
            Nested fields are deep-merged: e.g. {"gates": {"sanity": {"abort_magnitude": 0.5}}}
            only changes that one threshold while keeping all other gate settings.
            
            Lists are replaced wholesale: e.g. {"adjustment_set": ["age", "income"]}
            replaces the entire adjustment set.""")
        Map<String, Object> specPatch

    ) {
        try {
            log.info("Re-executing causal pipeline run '{}' with patch on fields: {}",
                runId, specPatch.keySet());

            var result = mlService.reexecutePipeline(runId, specPatch);

            var reexecutedSteps = result.get("reexecuted_steps");
            var skippedSteps = result.get("skipped_steps");
            log.info("Re-execution complete: new_run={}, reexecuted={}, skipped={}",
                result.get("run_id"), reexecutedSteps, skippedSteps);

            return writeJson(result);
        } catch (MlServiceException e) {
            log.error("Pipeline re-execution failed for run '{}'", runId, e);
            return errorResponse("Re-execution failed: " + e.getMessage());
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
