package com.rorm.ai;

import com.rorm.ai.tools.DataOverviewToolFactory;
import com.rorm.ai.tools.QueryExecutionToolFactory;
import com.rorm.metamodel.ModelSpace;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ChatModel;

@RequiredArgsConstructor
public class RormAiServiceFactory {

    private final ChatModel chatModel;
    private final RormAiProperties properties;
    private final QueryExecutionToolFactory queryExecutionToolFactory;
    private final DataOverviewToolFactory dataOverviewToolFactory;

    public RormAiService create(ModelSpace modelSpace) {
        var queryExecutionTool = queryExecutionToolFactory.create(modelSpace);
        var dataOverviewTool = dataOverviewToolFactory.create(modelSpace);
        return new RormAiService(chatModel, modelSpace, properties, queryExecutionTool, dataOverviewTool);
    }
}
