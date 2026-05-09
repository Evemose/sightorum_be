package com.rorm.client.chat.tool;

import com.rorm.ai.RormToolContext;
import com.rorm.client.chat.AnalysisService;
import com.rorm.client.chat.session.AnalysisKind;
import com.rorm.client.chat.session.SessionService;
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
    private final SessionService sessionService;

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
        @ToolParam(
            description = """
                Optional explicit anchor entity names from the schema to focus the analysis on.
                Pass null or omit to let the swarm propose anchors automatically by merging the scout's
                data-driven proposals with the domain researcher's literature-driven proposals.
                You should only specify the anchors if user specified their specific areas of interest,
                otherwise let the swarm propose anchors automatically, as it is usually more robust.
                DO NOT make up anchors unless user explicitly asks for it, you are NOT smarter then the swarm when it comes to proposing anchors,
                the swarm has access to the data and scientific literature and can propose much better anchors then you can.
                """,
            required = false)
        List<String> anchors,
        ToolContext toolContext
    ) {
        var ctx = RormToolContext.from(toolContext);
        var sessionId = (String) toolContext.getContext().get("sessionId");
        var runId = analysisService.startAnalysis(ctx.schema(), query, anchors);
        if (sessionId != null) {
            sessionService.registerAnalysis(sessionId, runId, AnalysisKind.CAUSAL, query);
        }
        return "Causal analysis started. Run ID: " + runId +
               ". The user can follow progress at /research/" + runId + "/stream (SSE endpoint).";
    }
}
