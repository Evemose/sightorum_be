package com.rorm.ai.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rorm.ai.DataOverviewService;
import com.rorm.ai.RormAiProperties;
import com.rorm.engine.ExpressionTypeResolver;
import com.rorm.engine.QueryTransformer;
import com.rorm.mapper.ExpressionMapper;
import com.rorm.metamodel.ModelSpace;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;

@RequiredArgsConstructor
public class DataOverviewToolFactory {

    private final DataOverviewService dataOverviewService;
    private final ExpressionTypeResolver typeResolver;
    private final ExpressionMapper expressionMapper;
    private final QueryTransformer queryTransformer;
    private final DSLContext dsl;
    private final ObjectMapper objectMapper;
    private final RormAiProperties properties;

    public DataOverviewTool create(ModelSpace modelSpace) {
        return new DataOverviewTool(
            dataOverviewService,
            typeResolver,
            expressionMapper,
            queryTransformer,
            dsl,
            objectMapper,
            properties,
            modelSpace
        );
    }

}
