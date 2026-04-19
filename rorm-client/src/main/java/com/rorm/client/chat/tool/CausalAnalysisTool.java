package com.rorm.client.chat.tool;

import com.rorm.ai.RormToolContext;
import com.rorm.client.chat.AnalysisService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class CausalAnalysisTool {

    private final AnalysisService analysisService;

    @Tool(
        name = "startCausalAnalysis",
        description = """
            Launch a deep causal analysis pipeline that investigates cause-effect \
            relationships in the data through a multi-phase swarm: reconnaissance \
            (domain research + data survey), hypothesis generation with adversarial \
            review, pipeline compilation, ML execution, and forensic diagnostics. \
            This is a long-running process (minutes to hours). Returns a run ID \
            the user can use to follow progress via the event stream. \
            Use this when the user asks about causality, root causes, "why" questions, \
            or wants rigorous hypothesis testing beyond what summary statistics can answer."""
    )
    public String startCausalAnalysis(
        @ToolParam(description = "The causal research question to investigate")
        String query,
        @ToolParam(description = "Anchor entity names from the schema to focus the analysis on (e.g. root table names)")
        List<String> anchors,
        ToolContext toolContext
    ) {
        var ctx = RormToolContext.from(toolContext);
        var runId = analysisService.startAnalysis(ctx.schema(), ctx.modelSpace(), query, anchors);
        return "Causal analysis started. Run ID: " + runId +
               ". The user can follow progress at /datasets/" + ctx.schema() +
               "/analysis/" + runId + "/stream (SSE endpoint).";
    }
}
