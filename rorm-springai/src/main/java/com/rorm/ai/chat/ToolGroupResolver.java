package com.rorm.ai.chat;

import com.rorm.ai.tools.DataOverviewTool;
import com.rorm.ai.tools.QueryExecutionTool;
import com.rorm.ml.tools.DataRelationsTool;
import com.rorm.ml.tools.MlTrainingTool;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class ToolGroupResolver {

    private final QueryExecutionTool queryExecutionTool;
    private final DataOverviewTool dataOverviewTool;
    private final MlTrainingTool mlTrainingTool;
    private final DataRelationsTool dataRelationsTool;

    public List<Object> resolve(Set<ToolGroup> groups) {
        var tools = new ArrayList<>();
        for (var group : groups) {
            switch (group) {
                case QUERY -> {
                    tools.add(queryExecutionTool);
                    tools.add(dataOverviewTool);
                }
                case ML -> tools.add(mlTrainingTool);
                case DATA_RELATIONS -> tools.add(dataRelationsTool);
                case WEB_ACCESS -> {
                    // Web access tools to be added when available
                }
            }
        }
        return tools;
    }
}
