package com.rorm.testutil;

import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.DriverManager;
import java.util.UUID;

@SuppressWarnings("SqlNoDataSourceInspection")
@Testcontainers
public abstract class AbstractPostgresTest {

    @Container
    @SuppressWarnings("resource")
    protected static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine")
        .withDatabaseName("testdb")
        .withUsername("test")
        .withPassword("test");

    private static final ThreadLocal<String> SCHEMA_NAME = new ThreadLocal<>();

    protected DSLContext dsl;

    @BeforeEach
    void setupDatabase() throws Exception {
        var schemaName = "test_" + UUID.randomUUID().toString().replace("-", "");
        SCHEMA_NAME.set(schemaName);

        try (var conn = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var stmt = conn.createStatement()) {
            stmt.execute("CREATE SCHEMA " + schemaName);
        }

        // do not close the DSLContext as it would close the underlying connection pool
        // noinspection resource
        var config = DSL.using(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()).configuration();
        config.settings().withRenderSchema(false);
        dsl = DSL.using(config).dsl();
        dsl.execute("SET search_path TO " + schemaName);

        afterDatabaseSetup();
    }

    protected void afterDatabaseSetup() {
    }

    protected String getCurrentSchema() {
        return SCHEMA_NAME.get();
    }

    @AfterEach
    void cleanupDatabase() throws Exception {
        beforeDatabaseCleanup();

        var schemaName = SCHEMA_NAME.get();
        if (schemaName != null) {
            try (var conn = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                 var stmt = conn.createStatement()) {
                stmt.execute("DROP SCHEMA IF EXISTS " + schemaName + " CASCADE");
            }
            SCHEMA_NAME.remove();
        }
    }

    protected void beforeDatabaseCleanup() {
    }
}
