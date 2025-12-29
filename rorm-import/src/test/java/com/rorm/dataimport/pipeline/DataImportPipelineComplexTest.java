package com.rorm.dataimport.pipeline;

import com.rorm.dataimport.source.CsvDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class DataImportPipelineComplexTest extends AbstractImportTest {

    @TempDir
    Path tempDir;

    @Autowired
    JobExplorer jobExplorer;

    @Test
    @DisplayName("imports multiple CSV files creating multiple tables")
    void importMultipleFiles() throws Exception {
        var usersFile = tempDir.resolve("users.csv");
        Files.writeString(usersFile, """
            name,email
            Alice,alice@example.com
            Bob,bob@example.com
            """);

        var ordersFile = tempDir.resolve("orders.csv");
        Files.writeString(ordersFile, """
            order_number,total
            ORD-001,100.00
            ORD-002,200.00
            ORD-003,150.00
            """);

        var userSource = new CsvDataSource(usersFile);
        var orderSource = new CsvDataSource(ordersFile);

        var modelSpace = modelSpaceDetector.detectModelSpace(
            List.of(userSource, orderSource),
            List.of(),
            ";"
        );

        var schema = getSchemaName();
        var request = new ImportRequest(schema, List.of(userSource, orderSource), modelSpace);
        var result = dataImportPipeline.importData(request);

        assertThat(result.totalRowsImported()).isEqualTo(5);
        assertThat(result.modelSpace().roots()).hasSize(2);

        var userCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM %s.users".formatted(schema), Long.class);
        assertThat(userCount).isEqualTo(2);

        var orderCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM %s.orders".formatted(schema), Long.class);
        assertThat(orderCount).isEqualTo(3);

        userSource.close();
        orderSource.close();
    }

    @Test
    @DisplayName("processes large file in chunks using Spring Batch")
    void importLargeFileInChunks() throws Exception {
        var csvFile = tempDir.resolve("large.csv");
        var lines = new StringBuilder("id,value\n");
        for (int i = 1; i <= 5000; i++) {
            lines.append(i).append(",value").append(i).append("\n");
        }
        Files.writeString(csvFile, lines.toString());

        var dataSource = new CsvDataSource(csvFile);
        var modelSpace = modelSpaceDetector.detectModelSpace(
            List.of(dataSource),
            List.of(),
            ";"
        );

        var schema = getSchemaName();
        var request = new ImportRequest(schema, List.of(dataSource), modelSpace, 500); // chunk size 500
        var result = dataImportPipeline.importData(request);

        assertThat(result.totalRowsImported()).isEqualTo(5000);

        var count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM %s.large".formatted(schema), Long.class);
        assertThat(count).isEqualTo(5000);

        // Verify first and last rows
        var first = jdbcTemplate.queryForMap("SELECT * FROM %s.large WHERE id = 1".formatted(schema));
        assertThat(first.get("value")).isEqualTo("value1");

        var last = jdbcTemplate.queryForMap("SELECT * FROM %s.large WHERE id = 5000".formatted(schema));
        assertThat(last.get("value")).isEqualTo("value5000");

        dataSource.close();
    }

    @Test
    @DisplayName("Spring Batch job completes successfully")
    void verifyBatchJobCompletion() throws Exception {
        var csvFile = tempDir.resolve("batch_test.csv");
        Files.writeString(csvFile, """
            name,status
            Item1,active
            Item2,pending
            """);

        var dataSource = new CsvDataSource(csvFile);
        var modelSpace = modelSpaceDetector.detectModelSpace(
            List.of(dataSource),
            List.of(),
            ";"
        );

        var schema = getSchemaName();
        var request = new ImportRequest(schema, List.of(dataSource), modelSpace);
        dataImportPipeline.importData(request);

        // Verify job execution (job name includes timestamp, so search by prefix)
        var allInstances = jobExplorer.getJobNames();
        var jobInstances = allInstances.stream()
            .filter(name -> name.startsWith("import-job-batch_test"))
            .findFirst()
            .map(name -> jobExplorer.findJobInstancesByJobName(name, 0, 1))
            .orElseThrow();
        assertThat(jobInstances).isNotEmpty();

        var jobInstance = jobInstances.getFirst();
        var executions = jobExplorer.getJobExecutions(jobInstance);
        assertThat(executions).hasSize(1);

        var execution = executions.getFirst();
        assertThat(execution.getStatus()).isEqualTo(org.springframework.batch.core.BatchStatus.COMPLETED);
        assertThat(execution.getExitStatus().getExitCode()).isEqualTo("COMPLETED");

        dataSource.close();
    }

    @Test
    @DisplayName("Spring Batch step reports correct write count")
    void verifyBatchStepWriteCount() throws Exception {
        var csvFile = tempDir.resolve("step_test.csv");
        Files.writeString(csvFile, """
            value
            one
            two
            three
            four
            five
            """);

        var dataSource = new CsvDataSource(csvFile);
        var modelSpace = modelSpaceDetector.detectModelSpace(
            List.of(dataSource),
            List.of(),
            ";"
        );

        var schema = getSchemaName();
        var request = new ImportRequest(schema, List.of(dataSource), modelSpace, 2); // small chunks
        dataImportPipeline.importData(request);

        var allInstances = jobExplorer.getJobNames();
        var jobInstances = allInstances.stream()
            .filter(name -> name.startsWith("import-job-step_test"))
            .findFirst()
            .map(name -> jobExplorer.findJobInstancesByJobName(name, 0, 1))
            .orElseThrow();
        assertThat(jobInstances).isNotEmpty();

        var jobInstance = jobInstances.getFirst();
        var executions = jobExplorer.getJobExecutions(jobInstance);
        var execution = executions.getFirst();

        var stepExecution = execution.getStepExecutions().iterator().next();
        assertThat(stepExecution.getReadCount()).isEqualTo(5);
        assertThat(stepExecution.getWriteCount()).isEqualTo(5);
        assertThat(stepExecution.getCommitCount()).isGreaterThan(1); // Multiple chunks

        dataSource.close();
    }

    @Test
    @DisplayName("imports file with mixed id types gracefully")
    void importMixedIdTypes() throws Exception {
        var csvFile = tempDir.resolve("mixed_ids.csv");
        Files.writeString(csvFile, """
            id,name
            1,First
            invalid,Second
            3,Third
            """);

        var dataSource = new CsvDataSource(csvFile);
        var modelSpace = modelSpaceDetector.detectModelSpace(
            List.of(dataSource),
            List.of(),
            ";"
        );

        var schema = getSchemaName();
        var request = new ImportRequest(schema, List.of(dataSource), modelSpace);
        var result = dataImportPipeline.importData(request);

        assertThat(result.totalRowsImported()).isEqualTo(3);

        var rows = jdbcTemplate.queryForList("SELECT id, name FROM %s.mixed_ids ORDER BY id".formatted(schema));
        assertThat(rows).hasSize(3);

        // First row uses explicit id
        assertThat(rows.get(0).get("id")).isEqualTo(1L);
        assertThat(rows.get(0).get("name")).isEqualTo("First");

        // Second row falls back to sequential id (invalid id)
        assertThat(rows.get(1).get("id")).isEqualTo(2L);
        assertThat(rows.get(1).get("name")).isEqualTo("Second");

        // Third row uses explicit id
        assertThat(rows.get(2).get("id")).isEqualTo(3L);
        assertThat(rows.get(2).get("name")).isEqualTo("Third");

        dataSource.close();
    }

    @Test
    @DisplayName("imports data with special characters and quotes")
    void importSpecialCharacters() throws Exception {
        var csvFile = tempDir.resolve("special.csv");
        Files.writeString(csvFile, """
            name,description
            "Product, Inc","This is a ""quoted"" value"
            Test & Co,"Line 1
            Line 2"
            """);

        var dataSource = new CsvDataSource(csvFile);
        var modelSpace = modelSpaceDetector.detectModelSpace(
            List.of(dataSource),
            List.of(),
            ";"
        );

        var schema = getSchemaName();
        var request = new ImportRequest(schema, List.of(dataSource), modelSpace);
        dataImportPipeline.importData(request);

        var rows = jdbcTemplate.queryForList("SELECT * FROM %s.special ORDER BY id".formatted(schema));
        assertThat(rows).hasSize(2);

        assertThat(rows.get(0).get("name")).isEqualTo("Product, Inc");
        assertThat(rows.get(0).get("description")).isEqualTo("This is a \"quoted\" value");

        assertThat(rows.get(1).get("name")).isEqualTo("Test & Co");
        assertThat(rows.get(1).get("description")).isEqualTo("Line 1\nLine 2");

        dataSource.close();
    }

    @Test
    @DisplayName("different chunk sizes produce same result")
    void verifyChunkSizeIndependence() throws Exception {
        var csvFile = tempDir.resolve("chunk_test.csv");
        var lines = new StringBuilder("value\n");
        for (int i = 1; i <= 100; i++) {
            lines.append("value").append(i).append("\n");
        }
        Files.writeString(csvFile, lines.toString());

        // Import with chunk size 10
        var dataSource1 = new CsvDataSource(csvFile);
        var modelSpace1 = modelSpaceDetector.detectModelSpace(List.of(dataSource1), List.of(), ";");
        var schema1 = "chunk_10_" + System.currentTimeMillis();
        var request1 = new ImportRequest(schema1, List.of(dataSource1), modelSpace1, 10);

        // Import with chunk size 25
        var dataSource2 = new CsvDataSource(csvFile);
        var modelSpace2 = modelSpaceDetector.detectModelSpace(List.of(dataSource2), List.of(), ";");
        var schema2 = "chunk_25_" + System.currentTimeMillis();
        var request2 = new ImportRequest(schema2, List.of(dataSource2), modelSpace2, 25);

        try {
            dataImportPipeline.importData(request1);
            dataImportPipeline.importData(request2);

            var count1 = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + schema1 + ".chunk_test",
                Long.class
            );
            var count2 = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + schema2 + ".chunk_test",
                Long.class
            );

            assertThat(count1).isEqualTo(100);
            assertThat(count2).isEqualTo(100);

        } finally {
            jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + schema1 + " CASCADE");
            jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + schema2 + " CASCADE");
            dataSource1.close();
            dataSource2.close();
        }
    }
}
