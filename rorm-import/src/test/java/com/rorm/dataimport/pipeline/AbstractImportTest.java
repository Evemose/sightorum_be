package com.rorm.dataimport.pipeline;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

@SuppressWarnings({"SqlNoDataSourceInspection", "SqlSourceToSinkFlow"})
@SpringBootTest
@Testcontainers
public abstract class AbstractImportTest {

    @Container
    @SuppressWarnings("resource")
    protected static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine")
        .withDatabaseName("testdb")
        .withUsername("test")
        .withPassword("test");

    @Autowired
    protected ModelSpaceDetector modelSpaceDetector;

    @Autowired
    protected DataImportPipeline dataImportPipeline;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    protected String testSchema;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.batch.jdbc.initialize-schema", () -> "always");
    }

    @BeforeEach
    void setupTestSchema() {
        testSchema = "test_" + UUID.randomUUID().toString().replace("-", "");
        jdbcTemplate.execute("CREATE SCHEMA " + testSchema);
    }

    @AfterEach
    void cleanupTestSchema() {
        if (testSchema != null) {
            jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + testSchema + " CASCADE");
        }
    }

    protected String getSchemaName() {
        return testSchema;
    }
}
