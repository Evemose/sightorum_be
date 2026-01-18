package com.rorm.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rorm.ai.tools.DataOverviewToolFactory;
import com.rorm.ai.tools.QueryExecutionToolFactory;
import com.rorm.engine.ExpressionTypeResolver;
import com.rorm.engine.QueryTransformer;
import com.rorm.mapper.ExpressionMapper;
import com.rorm.mapper.QueryMapper;
import org.jooq.DSLContext;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnClass(ChatModel.class)
@EnableConfigurationProperties(RormAiProperties.class)
public class RormAiAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public MetamodelContextBuilder metamodelContextBuilder(RormAiProperties properties) {
        return new MetamodelContextBuilder(properties.includeLocationDetails());
    }

    @Bean
    @ConditionalOnMissingBean
    public DataOverviewService dataOverviewService(ExpressionTypeResolver typeResolver) {
        return new DataOverviewService(typeResolver);
    }

    @Bean
    @ConditionalOnMissingBean
    public QueryExecutionToolFactory queryExecutionToolFactory(
        QueryTransformer queryTransformer,
        DSLContext dsl,
        ObjectMapper objectMapper,
        RormAiProperties properties,
        QueryMapper queryMapper
    ) {
        return new QueryExecutionToolFactory(queryTransformer, dsl, objectMapper, properties, queryMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public DataOverviewToolFactory dataOverviewToolFactory(
        DataOverviewService dataOverviewService,
        ExpressionTypeResolver typeResolver,
        ExpressionMapper expressionMapper,
        QueryTransformer queryTransformer,
        DSLContext dsl,
        ObjectMapper objectMapper,
        RormAiProperties properties
    ) {
        return new DataOverviewToolFactory(
            dataOverviewService,
            typeResolver,
            expressionMapper,
            queryTransformer,
            dsl,
            objectMapper,
            properties
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public RormAiServiceFactory rormAiServiceFactory(
        ChatModel chatModel,
        RormAiProperties properties,
        QueryExecutionToolFactory queryExecutionToolFactory,
        DataOverviewToolFactory dataOverviewToolFactory
    ) {
        return new RormAiServiceFactory(chatModel, properties, queryExecutionToolFactory, dataOverviewToolFactory);
    }
}
