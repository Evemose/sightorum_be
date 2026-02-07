package com.rorm.dataimport.pipeline;

import com.rorm.dataimport.hierarchical.HierarchicalSchemaConverter;
import com.rorm.dataimport.naming.NamingStyleDetector;
import com.rorm.dataimport.type.DataTypeDetector;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Optional;

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
    HierarchicalSchemaConverter hierarchicalSchemaConverter() {
        return new HierarchicalSchemaConverter();
    }

    @Bean
    @ConditionalOnMissingBean
    FlatDetectionStrategy flatDetectionStrategy(
        NamingStyleDetector namingStyleDetector,
        DataTypeDetector dataTypeDetector
    ) {
        return new FlatDetectionStrategy(namingStyleDetector, dataTypeDetector);
    }

    @Bean
    @ConditionalOnMissingBean
    HierarchicalDetectionStrategy hierarchicalDetectionStrategy(
        HierarchicalSchemaConverter hierarchicalSchemaConverter
    ) {
        return new HierarchicalDetectionStrategy(hierarchicalSchemaConverter);
    }

    @Bean
    @ConditionalOnMissingBean
    SchemaDetector schemaDetector(
        FlatDetectionStrategy flatDetectionStrategy,
        HierarchicalDetectionStrategy hierarchicalDetectionStrategy
    ) {
        return new SchemaDetector(flatDetectionStrategy, hierarchicalDetectionStrategy);
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
    ModelSpaceDetector modelSpaceDetector(SchemaDetector schemaDetector) {
        return new ModelSpaceDetector(schemaDetector);
    }

    @Bean
    @ConditionalOnMissingBean
    DataImportPipeline dataImportPipeline(
        JdbcTemplate jdbcTemplate,
        JobLauncher jobLauncher,
        JobRepository jobRepository,
        PlatformTransactionManager transactionManager,
        @ImportTaskExecutor Optional<TaskExecutor> importTaskExecutor,
        ObjectProvider<TaskExecutor> defaultTaskExecutor,
        SchemaGenerator schemaGenerator,
        MetamodelConverter metamodelConverter,
        TransactionTemplate transactionTemplate
    ) {
        return new DataImportPipeline(
            jdbcTemplate,
            jobLauncher,
            jobRepository,
            transactionManager,
            schemaGenerator,
            importTaskExecutor.orElseGet(() -> defaultTaskExecutor.getIfUnique(SimpleAsyncTaskExecutor::new)),
            metamodelConverter,
            transactionTemplate
        );
    }

    @Qualifier
    @Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD, ElementType.TYPE})
    @Retention(RetentionPolicy.RUNTIME)
    public @interface ImportTaskExecutor {
    }
}
