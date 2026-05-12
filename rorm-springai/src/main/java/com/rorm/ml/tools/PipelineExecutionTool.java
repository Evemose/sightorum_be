package com.rorm.ml.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rorm.ai.DeferredToolResult;
import com.rorm.ai.RormToolContext;
import com.rorm.ai.swarm.communication.SwarmToolContext;
import com.rorm.ml.MlTrainingService;
import com.rorm.ml.PipelineSpecConverter;
import com.rorm.ml.dto.PipelineSpecPatch;
import com.rorm.ml.dto.PipelineSpecRequest;
import com.rorm.ml.dto.RunRecord;
import com.rorm.ml.exception.MlServiceException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class PipelineExecutionTool {

    private final MlTrainingService mlService;
    private final PipelineSpecConverter pipelineSpecConverter;
    private final ObjectMapper objectMapper;

    @Tool(
        name = "executePipeline",
        description = """
            Submit a fresh causal verification pipeline from a complete
            PipelineSpecRequest. Returns the resolved JobEvent (status,
            metrics, gates, refutations, error info) once the pipeline
            finishes.
            
            Use this as your primary spec-evaluation tool: produce a
            candidate spec, execute it, inspect the JobEvent, revise the
            spec or call executePipeline again. Each call is independent
            — the new run does NOT inherit anything from previous runs.
            To CHANGE only specific fields of an already-completed run
            while reusing its checkpoints, use reexecuteCausalPipeline.
            
            You may invoke executePipeline multiple times in parallel
            within a single tool-calling round to compare alternative
            spec variants simultaneously; downstream agents will see
            every run via runIds the next phase queries with
            queryRunSpec / queryRunResult."""
    )
    public String executePipeline(

        @ToolParam(description = """
            The complete pipeline spec to run. All fields the pipeline
            requires must be present — this is NOT a patch.""")
        PipelineSpecRequest spec,

        ToolContext toolContext

    ) {
        var ctx = RormToolContext.from(toolContext);
        var swarmCtx = SwarmToolContext.from(toolContext);
        try {
            log.info("Submitting fresh pipeline execution: hypothesis_id={}", spec.hypothesisId());
            var specHolder = swarmCtx.pipelineSpecHolder();
            if (specHolder != null) {
                specHolder.set(spec);
            }
            var request = pipelineSpecConverter.convert(
                spec, spec.hypothesisId() + " causal verification",
                ctx.modelSpace(), ctx.schema());
            var future = mlService.submit(request, ctx.stepJournal());
            var registry = swarmCtx.toolCallRegistry();
            return DeferredToolResult.defer(toolContext, future.map(jobEvent -> {
                if (registry != null) {
                    registry.record(swarmCtx.runId(), swarmCtx.askerEventId(),
                        "executePipeline", spec, jobEvent);
                }
                return writeJson(jobEvent);
            }));
        } catch (MlServiceException e) {
            log.error("Pipeline execution failed for hypothesis '{}'", spec.hypothesisId(), e);
            return errorResponse("Pipeline execution failed: " + e.getMessage());
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
        name = "reexecuteCausalPipeline",
        description = """
            Re-execute a base run with a PARTIAL spec change — only steps
            affected by the changed fields are re-computed; unaffected
            steps load from the base run's checkpoints. Returns the
            resolved JobEvent for the new run with per-step diffs.
            
            The base run must be one you can see in this step's registry
            (a run produced by an upstream step, visible via its
            runBriefs, or one you produced earlier in this step). The
            base spec is read from the registry; you provide only the
            patch.
            
            Dependency DAG (which fields invalidate which steps):
            - datasource, strip_columns → load_data → cascades downstream
            - dag_edges, dsep_threshold → dsep → estimation, refutations,
              sensitivity, residual_diagnostics
            - adjustment_set, mediators_excluded → identification → gates,
              mediation, grf, range_checks, residual_diagnostics
            - estimation_variants → estimation → gates, mediation,
              refutations, sensitivity, structural_breaks
            - gates → gates, refutations
            - sensitivity → sensitivity
            - grf_configs → grf → externalization
            - refutations → refutations
            - range_checks → range_checks
            - residual_checks → residual_diagnostics
            - externalization → externalization
            
            The new run is itself queryable via queryRunSpec /
            queryRunResult."""
    )
    public String reexecuteCausalPipeline(

        @ToolParam(description = """
            Run id of the base run to re-execute against. Must be a run
            visible in this step's registry (upstream or own).""")
        String runId,

        @ToolParam(description = """
            Partial spec patch — include ONLY the fields you want to
            change. Omitted fields keep the base run's values. Nested
            objects are deep-merged; lists are replaced wholesale.""")
        PipelineSpecPatch specPatch,

        ToolContext toolContext

    ) {
        var ctx = RormToolContext.from(toolContext);
        var swarmCtx = SwarmToolContext.from(toolContext);
        var registry = swarmCtx.toolCallRegistry();
        if (registry == null) {
            return errorResponse("No tool-call registry installed; reexecute is unavailable in this step.");
        }
        var base = registry.find(runId).orElse(null);
        if (base == null) {
            return errorResponse("Base run " + runId + " not found in registry "
                                 + "(only runs produced earlier in this swarm are reexecutable).");
        }
        var baseSpec = base.request() instanceof PipelineSpecRequest s ? s : null;
        if (baseSpec == null) {
            return errorResponse("Base run " + runId + " has no recorded spec "
                                 + "(it was itself launched as a reexecute patch). "
                                 + "Re-execute against an earlier ancestor that holds the full spec.");
        }
        try {
            log.info("Submitting reexecution: base_run={} hypothesis_id={}",
                runId, baseSpec.hypothesisId());
            var reexecRequest = new RunRecord.ReexecuteRequest(runId, specPatch);
            var future = mlService.reexecuteWithBase(
                new MlTrainingService.ReexecuteWithBaseRequest(runId, baseSpec, specPatch),
                ctx);
            return DeferredToolResult.defer(toolContext, future.map(jobEvent -> {
                registry.record(swarmCtx.runId(), swarmCtx.askerEventId(),
                    "reexecuteCausalPipeline", reexecRequest, jobEvent);
                return writeJson(jobEvent);
            }));
        } catch (MlServiceException e) {
            log.error("Pipeline re-execution failed for base run '{}'", runId, e);
            return errorResponse("Re-execution failed: " + e.getMessage());
        }
    }
}
