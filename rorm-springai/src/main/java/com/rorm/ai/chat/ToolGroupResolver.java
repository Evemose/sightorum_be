package com.rorm.ai.chat;

import com.rorm.ai.tools.DataOverviewTool;
import com.rorm.ai.tools.FeatureEngineeringTool;
import com.rorm.ai.tools.QueryExecutionTool;
import com.rorm.ml.tools.DataRelationsTool;
import com.rorm.ml.tools.MlTrainingTool;
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
    private final FeatureEngineeringTool featureEngineeringTool;

    public Set<Object> resolve(Set<ToolGroup> groups) {
        var tools = new HashSet<>();
        for (var group : groups) {
            switch (group) {
                case QUERY -> {
                    tools.add(queryExecutionTool);
                    tools.add(dataOverviewTool);
                    tools.add(featureEngineeringTool);
                }
                case ML -> tools.add(mlTrainingTool);
                case DATA_RELATIONS -> {
                    tools.add(dataRelationsTool);
                    tools.add(featureEngineeringTool);
                }
                case WEB_ACCESS -> {
                    // Web access tools to be added when available
                }
            }
        }
        return tools;
    }
}
