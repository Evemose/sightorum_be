package com.rorm.dataimport.pipeline;

import com.rorm.dataimport.naming.NamingStyleDetector;
import com.rorm.dataimport.type.DataTypeDetector;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@AutoConfiguration
public class RormImportAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    NamingStyleDetector namingStyleDetector() {
        return new NamingStyleDetector();
    }

    @Bean
    @ConditionalOnMissingBean
    DataTypeDetector dataTypeDetector() {
        return new DataTypeDetector();
    }

    @Bean
    @ConditionalOnMissingBean
    SchemaDetector schemaDetector(NamingStyleDetector namingStyleDetector, DataTypeDetector dataTypeDetector) {
        return new SchemaDetector(namingStyleDetector, dataTypeDetector);
    }

    @Bean
    @ConditionalOnMissingBean
    MetamodelConverter metamodelConverter() {
        return new MetamodelConverter();
    }

    @Bean
    @ConditionalOnMissingBean
    SchemaGenerator schemaGenerator() {
        return new SchemaGenerator();
    }

    @Bean
    @ConditionalOnMissingBean
    ModelSpaceDetector modelSpaceDetector(SchemaDetector schemaDetector, MetamodelConverter metamodelConverter) {
        return new ModelSpaceDetector(schemaDetector, metamodelConverter);
    }

    @Bean
    @ConditionalOnMissingBean
    DataImportPipeline dataImportPipeline(
        JdbcTemplate jdbcTemplate,
        JobLauncher jobLauncher,
        JobRepository jobRepository,
        PlatformTransactionManager transactionManager,
        SchemaGenerator schemaGenerator
    ) {
        return new DataImportPipeline(jdbcTemplate, jobLauncher, jobRepository, transactionManager, schemaGenerator);
    }
}
