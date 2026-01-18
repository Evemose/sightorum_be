package com.rorm.ai;

import com.rorm.dataimport.override.SchemaOverride;
import com.rorm.dataimport.pipeline.DataImportPipeline;
import com.rorm.dataimport.pipeline.ImportRequest;
import com.rorm.dataimport.pipeline.ModelSpaceDetector;
import com.rorm.dataimport.source.CsvDataSource;
import com.rorm.dataimport.source.ImportDataSource;
import com.rorm.metamodel.ModelSpace;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Configuration
@ConditionalOnProperty(name = "rorm.ai.demo.enabled", havingValue = "true")
public class SampleAiWorkflowRunner {

    @Bean(defaultCandidate = false, name = "importedModelSpace")
    @ConditionalOnProperty(name = "rorm.ai.demo.import-data", havingValue = "true")
    public ModelSpace importedModelSpace(
        ModelSpaceDetector modelSpaceDetector,
        DataImportPipeline dataImportPipeline
    ) throws Exception {
        log.info("Loading courses dataset...");

        List<ImportDataSource> dataSources = loadCoursesCsvFiles();
        if (dataSources.isEmpty()) {
            log.warn("No CSV files found, falling back to empty ModelSpace");
            return new ModelSpace(java.util.Set.of());
        }

        var overrides = Map.of(
            "students", List.<SchemaOverride>of(
                new SchemaOverride.IdAttributeOverride("id", "student_id", null)
            ),
            "courses", List.<SchemaOverride>of(
                new SchemaOverride.IdAttributeOverride("id", "course_id", null)
            ),
            "enrollments", List.<SchemaOverride>of(
                new SchemaOverride.IdAttributeOverride("id", "enrollment_id", null)
            ),
            "reviews", List.<SchemaOverride>of(
                new SchemaOverride.IdAttributeOverride("id", "review_id", null)
            ),
            "attendance", List.<SchemaOverride>of(
                new SchemaOverride.IdAttributeOverride("id", "attendance_id", null)
            )
        );

        var modelSpace = modelSpaceDetector.detectModelSpace(dataSources, overrides, ",");

        log.info("Detected {} entities from CSV files", modelSpace.roots().size());
        for (var root : modelSpace.roots()) {
            log.info("  - {} ({} attributes)", root.primaryTableName(), root.attributes().size());
        }

        var request = new ImportRequest("public", dataSources, modelSpace, 500);
        var result = dataImportPipeline.importData(request);

        log.info("Imported {} total rows", result.totalRowsImported());

        for (var ds : dataSources) {
            ds.close();
        }

        return modelSpace;
    }

    @SneakyThrows
    private List<ImportDataSource> loadCoursesCsvFiles() {
        var resolver = new PathMatchingResourcePatternResolver();
        var dataSources = new ArrayList<ImportDataSource>();
        var resources = resolver.getResources("classpath:data/courses/*.csv");
        for (Resource resource : resources) {
            if (resource.exists() && resource.isReadable()) {
                log.info("Loading CSV: {}", resource.getFilename());
                dataSources.add(new CsvDataSource(resource.getFile().toPath()));
            }
        }
        return dataSources;
    }

    @Bean
    public CommandLineRunner aiWorkflowDemo(
        RormAiServiceFactory aiServiceFactory,
        @Qualifier("importedModelSpace") ModelSpace importedModelSpace
    ) {
        return _ -> {
            var aiService = aiServiceFactory.create(importedModelSpace);

            log.info("=".repeat(60));
            log.info("RORM Spring AI Demo - Natural Language Database Queries");
            log.info("=".repeat(60));

            log.info("\n[Schema Context Provided to AI]");
            log.info("-".repeat(40));
            var schemaPreview = aiService.getSystemPrompt();
            if (schemaPreview.length() > 2000) {
                log.info("{}\n... (truncated)", schemaPreview.substring(0, 2000));
            } else {
                log.info(schemaPreview);
            }

            var sampleQuestions = new String[]{
                "What tables are available in the database?",
                "What are the top 5 courses by enrollment count?",
                "Tell me one interesting numeric insight about this dataset.",
                "Tell me one interesting categorical insight about this dataset.",
                "Tell me one interesting temporal insight about this dataset."
            };

            log.info("\n[Running Sample AI Queries]");
            log.info("-".repeat(40));

            for (int i = 0; i < sampleQuestions.length; i++) {
                String question = sampleQuestions[i];
                log.info("\nQuestion {}: {}", i + 1, question);
                log.info("-".repeat(40));

                try {
                    String response = aiService.ask(question);
                    log.info("AI Response:\n{}", response);
                } catch (Exception e) {
                    log.error("Error processing question: {}. Cause: {}", e.getMessage(), e.getCause().getMessage());
                }

                log.info("");
            }

            log.info("=".repeat(60));
            log.info("Demo Complete!");
            log.info("=".repeat(60));
        };
    }
}
