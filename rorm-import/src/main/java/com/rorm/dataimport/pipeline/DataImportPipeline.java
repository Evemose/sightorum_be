package com.rorm.dataimport.pipeline;

import com.rorm.dataimport.source.ImportDataSource;
import com.rorm.metamodel.Root;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
@RequiredArgsConstructor
public class DataImportPipeline {

    private final JdbcTemplate jdbcTemplate;
    private final JobLauncher jobLauncher;
    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;

    public ImportResult importData(ImportRequest request) throws Exception {
        createSchema(request.targetSchema());

        var totalRows = 0L;
        for (var dataSource : request.dataSources()) {
            var root = findRootForDataSource(request, dataSource);
            var columnNames = dataSource.getColumnNames();

            createTable(request.targetSchema(), root, columnNames);
            var rowCount = executeImportJob(request, dataSource, columnNames);
            totalRows += rowCount;
        }

        return new ImportResult(request.targetSchema(), request.modelSpace(), totalRows);
    }

    private void createSchema(String schemaName) {
        jdbcTemplate.execute("CREATE SCHEMA IF NOT EXISTS %s".formatted(schemaName));
    }

    private Root findRootForDataSource(ImportRequest request, ImportDataSource dataSource) {
        return request.modelSpace().roots().stream()
            .filter(root -> root.primaryTableName().equals(dataSource.getRootName()))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "No root found in ModelSpace for data source: " + dataSource.getRootName()));
    }

    private void createTable(String schemaName, Root root, List<String> columnNames) {
        var qualifiedTableName = "%s.%s".formatted(schemaName, root.primaryTableName());
        var columnDefinitions = Stream.concat(
            Stream.of("id BIGINT PRIMARY KEY"),
            columnNames.stream()
                .filter(col -> !col.equalsIgnoreCase("id"))
                .map("%s TEXT"::formatted)
        ).collect(Collectors.joining(", "));

        var sql = "CREATE TABLE %s (%s)".formatted(qualifiedTableName, columnDefinitions);
        jdbcTemplate.execute(sql);
    }

    private long executeImportJob(
        ImportRequest request,
        ImportDataSource dataSource,
        List<String> columnNames
    ) throws Exception {
        var reader = new DataSourceItemReader(dataSource);
        var writer = new DatabaseItemWriter(
            jdbcTemplate,
            request.targetSchema(),
            dataSource.getRootName(),
            columnNames
        );

        var step = new StepBuilder("import-" + dataSource.getRootName(), jobRepository)
            .<Map<String, String>, Map<String, String>>chunk(request.chunkSize(), transactionManager)
            .reader(reader)
            .writer(writer)
            .build();

        var jobName = "import-job-" + dataSource.getRootName() + "-" + UUID.randomUUID();
        var job = new JobBuilder(jobName, jobRepository)
            .start(step)
            .build();

        var execution = jobLauncher.run(job, new JobParameters());
        return execution.getStepExecutions().stream()
            .mapToLong(StepExecution::getWriteCount)
            .sum();
    }
}
