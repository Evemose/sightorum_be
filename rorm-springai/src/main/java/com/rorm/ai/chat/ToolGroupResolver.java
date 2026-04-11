package com.rorm.ai.chat;

import com.rorm.ai.tools.*;
import com.rorm.ml.tools.CausalReexecutionTool;
import com.rorm.ml.tools.DataRelationsTool;
import com.rorm.ml.tools.MlTrainingTool;
import com.rorm.ml.tools.PipelineValidationTool;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class ToolGroupResolver {

    private final QueryExecutionTool queryExecutionTool;
    private final DataOverviewTool dataOverviewTool;
    private final MlTrainingTool mlTrainingTool;
    private final DataRelationsTool dataRelationsTool;
    private final CausalReexecutionTool causalReexecutionTool;
    private final PipelineValidationTool pipelineValidationTool;
    private final FeatureEngineeringTool featureEngineeringTool;
    private final HypothesisVerificationTool hypothesisVerificationTool;
    private final DataExplorationTool explorationTool;
    private final SummaryStatTool summaryStatTool;
    private final RankingTool rankingTool;
    private final TrendTool trendTool;
    private final ComparisonTool comparisonTool;

    public Set<Object> resolve(Set<ToolGroup> groups) {
        var tools = new HashSet<>();
        for (var group : groups) {
            switch (group) {
                case QUERY -> {
                    tools.add(queryExecutionTool);
                    tools.add(dataOverviewTool);
                    tools.add(featureEngineeringTool);
                    tools.add(explorationTool);
                    tools.add(summaryStatTool);
                    tools.add(rankingTool);
                    tools.add(trendTool);
                    tools.add(comparisonTool);
                }
                case VERIFICATION -> tools.add(hypothesisVerificationTool);
                case ML -> tools.add(mlTrainingTool);
                case DATA_RELATIONS -> {
                    tools.add(dataRelationsTool);
                    tools.add(featureEngineeringTool);
                }
                case CAUSAL_REEXECUTION -> tools.add(causalReexecutionTool);
                case PIPELINE_VALIDATION -> tools.add(pipelineValidationTool);
                case WEB_ACCESS -> {
                    // Web access tools to be added when available
                }
            }
        }
        return tools;
    }
}
