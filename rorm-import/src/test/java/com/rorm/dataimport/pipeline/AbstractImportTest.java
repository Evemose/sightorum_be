package com.rorm.dataimport.pipeline;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

@SuppressWarnings({"SqlNoDataSourceInspection", "SqlSourceToSinkFlow"})
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
    "spring.batch.jdbc.initialize-schema=always",
    "spring.batch.jdbc.table-prefix=public.BATCH_"
})
public abstract class AbstractImportTest {

    @Container
    @ServiceConnection
    @SuppressWarnings("resource")
    protected static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine")
        .withDatabaseName("testdb")
        .withUsername("test")
        .withPassword("test")
        .withReuse(true);

    private final boolean reuseSchema;

    @Autowired
    protected ModelSpaceDetector modelSpaceDetector;
    @Autowired
    protected DataImportPipeline dataImportPipeline;
    @Autowired
    protected JdbcTemplate jdbcTemplate;
    protected String testSchema;

    protected AbstractImportTest() {
        this(false);
    }

    @SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection")
    protected AbstractImportTest(boolean reuseSchema) {
        this.reuseSchema = reuseSchema;
    }

    @BeforeEach
    protected synchronized void setupTestSchema() {
        if (reuseSchema && testSchema != null) {
            return;
        }
        testSchema = "test_" + UUID.randomUUID().toString().replace("-", "");
        jdbcTemplate.execute("CREATE SCHEMA " + testSchema);
    }

    @AfterEach
    protected synchronized void cleanupTestSchema() {
        if (testSchema != null && !reuseSchema) {
            jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + testSchema + " CASCADE");
        }
    }

    protected String getSchemaName() {
        return testSchema;
    }
}
