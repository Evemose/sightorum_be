package com.rorm.ml.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import com.jayway.jsonpath.spi.json.JacksonJsonProvider;
import com.jayway.jsonpath.spi.mapper.JacksonMappingProvider;
import com.rorm.ai.swarm.communication.SwarmToolContext;
import com.rorm.ai.swarm.communication.ToolCallRegistry;
import com.rorm.ml.dto.PipelineSpecRequest;
import com.rorm.ml.dto.RunRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * In-memory JSONPath slice queries against the spec and JobEvent
 * result of a pipeline run produced earlier in the same swarm run.
 * Looks up runs from the per-step {@link ToolCallRegistry} that the
 * calling phase populated with upstream runs and that the agent's own
 * tool calls extend.
 * <p>
 * Runs from prior swarm submits are NOT queryable — the run's spec /
 * result no longer round-trip through Valkey. Only step checkpoints
 * (used by {@code reexecuteCausalPipeline} to skip unaffected steps)
 * persist server-side.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RunQueryTool {

    private final ObjectMapper objectMapper;
    private Configuration jsonPathConfig;

    @Tool(
        name = "queryRunSpec",
        description = """
            Read a slice of a pipeline run's PipelineSpec (the request the
            run was launched with) using a JSONPath expression. Use this
            to inspect specific spec fields the run was configured with —
            adjustment_set, dag_edges, gates.nuisance_r2,
            estimation_variants[*].id, etc. — without paying for the full
            spec dump.
            
            Only runs produced earlier in the SAME swarm run are
            queryable (visible via the runIds in the upstream agent's
            briefs or your own executePipeline calls).
            
            JSONPath syntax (Jayway / standard):
              $                        — root
              $.adjustment_set         — top-level field
              $.estimation_variants[0] — array element
              $.estimation_variants[*].id — every variant's id
              $.gates.nuisance_r2      — nested object
              $..filter                — recursive descent
              $.adjustment_set[?(@ == 'vendorTier')] — predicate
            
            Returns the matched slice as JSON. Returns {"error":"..."} if
            the runId is unknown to this step's registry, the run has no
            recorded spec (a reexecution patch-only call), or the path
            matches nothing."""
    )
    public String queryRunSpec(

        @ToolParam(description = """
            Run id (= JobEvent.jobId) of a pipeline run produced in this
            swarm run — either by an upstream step (visible via that
            step's runBriefs) or by your own executePipeline /
            reexecuteCausalPipeline calls.""")
        String runId,

        @ToolParam(description = """
            JSONPath expression selecting which slice of the spec to
            return. Examples:
              $.adjustment_set
              $.estimation_variants[*].treatment_form
              $.gates.sanity.abort_magnitude""")
        String jsonPath,

        ToolContext toolContext

    ) {
        var entry = lookup(runId, toolContext);
        if (entry == null) {
            return errorResponse("Run " + runId + " not found in this step's registry "
                                 + "(only runs from this swarm run are queryable).");
        }
        if (!(entry.request() instanceof PipelineSpecRequest spec)) {
            return errorResponse("Run " + runId + " has no recorded full spec "
                                 + "(toolName=" + entry.toolName() + " carries a patch, not a complete spec; "
                                 + "query the base run's spec via its runId instead).");
        }
        return applyJsonPath(spec, jsonPath, "spec", runId);
    }

    private RunRecord lookup(String runId, ToolContext toolContext) {
        var swarm = SwarmToolContext.from(toolContext);
        var registry = swarm.toolCallRegistry();
        if (registry == null) {
            return null;
        }
        return registry.find(runId).orElse(null);
    }

    private String errorResponse(String message) {
        try {
            return objectMapper.writeValueAsString(Map.of("error", message));
        } catch (JsonProcessingException e) {
            return "{\"error\":\"" + message.replace("\"", "\\\"") + "\"}";
        }
    }

    private String applyJsonPath(Object source, String jsonPath, String section, String runId) {
        try {
            var sourceJson = objectMapper.writeValueAsString(source);
            var doc = JsonPath.using(jsonPathConfig()).parse(sourceJson);
            var matched = doc.read(jsonPath);
            return objectMapper.writeValueAsString(matched);
        } catch (PathNotFoundException e) {
            return errorResponse("JSONPath '" + jsonPath + "' matched nothing in " + section
                                 + " of run " + runId);
        } catch (JsonProcessingException e) {
            return errorResponse("Failed to serialize " + section + " slice: " + e.getMessage());
        } catch (RuntimeException e) {
            return errorResponse("Invalid JSONPath '" + jsonPath + "': " + e.getMessage());
        }
    }

    private Configuration jsonPathConfig() {
        if (jsonPathConfig == null) {
            jsonPathConfig = Configuration.builder()
                .jsonProvider(new JacksonJsonProvider(objectMapper))
                .mappingProvider(new JacksonMappingProvider(objectMapper))
                .build();
        }
        return jsonPathConfig;
    }

    @Tool(
        name = "queryRunResult",
        description = """
            Read a slice of a pipeline run's RESULT (the JobEvent's
            metrics, gates, refutations, sensitivity, structural breaks,
            GRF, residual diagnostics, etc.) using a JSONPath expression.
            
            Only runs produced earlier in the SAME swarm run are
            queryable.
            
            JSONPath syntax (Jayway / standard) — examples:
              $.metrics.final_effect
              $.metrics.final_ci
              $.metrics.steps.gates.nuisance_r2
              $.metrics.steps.refutations[?(@.tier == 3)]
              $.metrics..e_value
              $..ATE_EXCEEDS_CEILING
            
            Returns the matched slice as JSON. Returns {"error":"..."} if
            the runId is unknown, the run has no result yet, or the path
            matches nothing."""
    )
    public String queryRunResult(

        @ToolParam(description = """
            Run id (= JobEvent.jobId) of a pipeline run produced in this
            swarm run.""")
        String runId,

        @ToolParam(description = """
            JSONPath expression selecting which slice of the result to
            return. Examples:
              $.metrics.final_effect
              $.metrics.steps.refutations
              $.metrics..tier""")
        String jsonPath,

        ToolContext toolContext

    ) {
        var entry = lookup(runId, toolContext);
        if (entry == null) {
            return errorResponse("Run " + runId + " not found in this step's registry "
                                 + "(only runs from this swarm run are queryable).");
        }
        return applyJsonPath(entry.response(), jsonPath, "result", runId);
    }
}
