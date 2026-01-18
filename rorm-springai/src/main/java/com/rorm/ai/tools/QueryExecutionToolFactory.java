package com.rorm.ai.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rorm.ai.RormAiProperties;
import com.rorm.engine.QueryTransformer;
import com.rorm.mapper.QueryMapper;
import com.rorm.metamodel.ModelSpace;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;

@RequiredArgsConstructor
public class QueryExecutionToolFactory {

    private final QueryTransformer queryTransformer;
    private final DSLContext dsl;
    private final ObjectMapper objectMapper;
    private final RormAiProperties properties;
    private final QueryMapper queryMapper;

    public QueryExecutionTool create(ModelSpace modelSpace) {
        return new QueryExecutionTool(
            queryTransformer,
            dsl,
            objectMapper,
            properties,
            queryMapper,
            modelSpace
        );
    }
}
