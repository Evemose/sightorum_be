package com.rorm.ml.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rorm.ai.JournaledTool;
import com.rorm.ai.swarm.communication.SwarmToolContext;
import com.rorm.ml.MlTrainingService;
import com.rorm.ml.PipelineSpecConverter;
import com.rorm.ml.dto.PipelineSpecRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
@JournaledTool
@RequiredArgsConstructor
public class PipelineValidationTool {

    private final PipelineSpecConverter pipelineSpecConverter;
    private final MlTrainingService mlService;
    private final ObjectMapper objectMapper;

    @Tool(
        name = "validatePipelineSpec",
        description = """
            Validate a causal verification PipelineSpec against the live database
            WITHOUT running the pipeline.
            
            Loads the data from the query, then checks every column reference,
            variant ID cross-reference, threshold type, DAG node existence, and
            structural constraint. Returns all errors in one pass so you can fix
            everything at once.
            
            CALL THIS BEFORE submitting a pipeline run. It catches:
            - Missing columns in adjustment_set, w_columns, modifier_columns, etc.
            - Non-numeric threshold_value on BINARY_THRESHOLD variants
            - Dangling variant_id references in mediation, overlap, sensitivity
            - DAG edges referencing columns not in the query
            - Duplicate estimation variant IDs
            
            Much cheaper than a full pipeline run — only loads data, no DML fitting."""
    )
    public String validatePipelineSpec(

        @ToolParam(description = """
            The full PipelineSpec to validate. Uses a DenseQueryDto for the data query
            (same schema as executeQuery). All spec fields are checked against the
            columns returned by that query.""")
        PipelineSpecRequest spec,

        ToolContext toolContext

    ) {
        try {
            var context = SwarmToolContext.from(toolContext);
            var base = context.base();
            var request = pipelineSpecConverter.convert(
                spec, "validation-only", base.modelSpace(), base.schema());

            var result = mlService.validatePipelineSpec(request);
            var errors = result.errors() != null ? result.errors() : List.<String>of();

            if (result.valid()) {
                depositValidatedSpec(context, spec);
                log.info("Pipeline spec '{}' validated successfully", spec.hypothesisId());
                return writeJson(Map.of(
                    "valid", true,
                    "hypothesis_id", spec.hypothesisId(),
                    "message", "Spec is valid — all columns, variant references, and constraints check out. The engine has captured this spec; finish your reply with a brief confirmation."
                ));
            } else {
                log.warn("Pipeline spec '{}' validation failed with {} error(s)",
                    spec.hypothesisId(), errors.size());
                return writeJson(Map.of(
                    "valid", false,
                    "hypothesis_id", spec.hypothesisId(),
                    "error_count", errors.size(),
                    "errors", errors
                ));
            }
        } catch (Exception e) {
            log.error("Pipeline spec validation failed", e);
            return errorResponse("Validation failed: " + e.getMessage());
        }
    }

    private void depositValidatedSpec(SwarmToolContext context, PipelineSpecRequest spec) {
        var holder = context.pipelineSpecHolder();
        if (holder == null) {
            throw new IllegalStateException(
                "validatePipelineSpec called from a swarm step that did not install a PipelineSpecHolder; "
                + "askerRole=" + context.askerRole() + ", askerChatId=" + context.askerChatId()
                + ". The compiler step must run through StepExecutorSupport which provisions the holder.");
        }
        holder.set(spec);
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
}
