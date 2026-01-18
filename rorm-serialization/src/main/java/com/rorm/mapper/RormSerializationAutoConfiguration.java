package com.rorm.mapper;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class RormSerializationAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public MetamodelMapper metamodelMapper() {
        return new MetamodelMapperImpl();
    }

    @Bean
    @ConditionalOnMissingBean
    public PathResolver pathResolver() {
        return new PathResolver();
    }

    @Bean
    @ConditionalOnMissingBean
    public QueryMapper queryMapper(PathResolver pathResolver) {
        // MapStruct-generated class requires setter injection for dependencies
        var mapper = new QueryMapperImpl();
        mapper.setPathResolver(pathResolver);
        return mapper;
    }

    @Bean
    @ConditionalOnMissingBean
    public ExpressionMapper expressionMapper(QueryMapper queryMapper) {
        return new ExpressionMapper(queryMapper);
    }
}
