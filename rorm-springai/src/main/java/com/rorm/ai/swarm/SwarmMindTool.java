package com.rorm.ai.swarm;

import com.rorm.ai.swarm.SwarmMind.Finding.StepFinding;
import com.rorm.ai.swarm.SwarmMind.Granularity;
import com.rorm.ai.swarm.SwarmMind.SearchQuery;
import com.rorm.ai.swarm.SwarmMind.SearchResult;
import com.rorm.ai.swarm.dto.ResearchPlanDTO;
import com.rorm.ai.swarm.dto.StepRef;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.context.annotation.Description;

import java.util.ArrayList;

@RequiredArgsConstructor
public class SwarmMindTool {

    private final SwarmMind swarmMind;

    public static SwarmMindTool forStep(ResearchPlanDTO plan, StepRef stepRef, SwarmMind swarmMind) {
        return new SwarmMindTool(swarmMind) {

            @Tool
            @Override
            @Description("""
                Searches the vector store for relevant information to inform research steps.
                Automatically includes findings from previously executed steps in the same branch.
                """)
            public SearchResult search(
                @ToolParam(
                    description = """
                        Natural language query describing what information is needed.
                        Example: 'Which factors have the most significant impact on customer churn?'
                        """
                )
                String query
            ) {
                var superResult = super.search(query);
                return ensureBranchContext(superResult);
            }

            private SearchResult ensureBranchContext(SearchResult superResult) {
                var previousSteps = plan.branches().stream()
                    .filter(b -> b.branchId().equals(stepRef.branchId()))
                    .flatMap(b -> b.steps().stream().map(s ->
                        new StepRef(stepRef.branchId(), s.stepId()))
                    )
                    .takeWhile(s -> !s.stepId().equals(stepRef.stepId()))
                    .toList();
                var mutableFindings = new ArrayList<>(superResult.findings());
                for (var step : previousSteps) {
                    mutableFindings.add(
                        new StepFinding(swarmMind.getStepRawOutput(step), Granularity.STEP, step, swarmMind.getStepVariables(step))
                    );
                }
                return new SearchResult(mutableFindings);
            }

        };
    }

    @Tool
    @Description("Searches the vector store for relevant information to inform research steps")
    public SearchResult search(
        @ToolParam(
            description = """
                Natural language query describing what information is needed.
                Example: 'Which factors have the most significant impact on customer churn?'
                """
        )
        String query
    ) {
        return swarmMind.search(new SearchQuery(query, 5));
    }

}
