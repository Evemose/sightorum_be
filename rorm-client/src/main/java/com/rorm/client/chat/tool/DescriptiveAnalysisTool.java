package com.rorm.client.chat.tool;

import com.rorm.ai.RormToolContext;
import com.rorm.client.chat.AnalysisService;
import com.rorm.client.chat.session.AnalysisKind;
import com.rorm.client.chat.session.AnalysisPersistenceCoordinator;
import com.rorm.client.chat.session.SessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DescriptiveAnalysisTool {

    private final AnalysisService analysisService;
    private final SessionService sessionService;
    private final AnalysisPersistenceCoordinator persistenceCoordinator;

    @Tool(
        name = "startDescriptiveAnalysis",
        description = """
            Launch a descriptive analysis phase that produces a structured \
            dashboard digest (archetype classification, charts, narrative) \
            for a given question. Runs as a durable invocation — typically \
            minutes, not hours. Returns a run ID the user can follow via \
            the event stream. Use this when the user wants a visual/narrative \
            overview, distribution analysis, trend breakdown, or any \
            exploratory question that goes beyond single summary statistics."""
    )
    public String startDescriptiveAnalysis(
        @ToolParam(description = "The descriptive question to analyze")
        String query,
        ToolContext toolContext
    ) {
        if (query == null || query.isBlank()) {
            return "Error: 'query' is required and must be a non-empty descriptive question. "
                   + "Re-invoke startDescriptiveAnalysis with the user's question as the query argument.";
        }
        var ctx = RormToolContext.from(toolContext);
        var sessionId = (String) toolContext.getContext().get("sessionId");
        var runId = analysisService.startDescriptiveAnalysis(ctx.schema(), query);
        if (sessionId != null) {
            sessionService.registerAnalysis(sessionId, runId, AnalysisKind.DESCRIPTIVE, query);
            persistenceCoordinator.track(runId);
        }
        return "Descriptive analysis started. Run ID: " + runId +
               ". The user can follow progress at /research/" + runId + "/stream (SSE endpoint).";
    }
}
