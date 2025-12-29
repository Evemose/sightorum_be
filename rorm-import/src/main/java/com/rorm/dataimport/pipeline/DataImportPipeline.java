package com.rorm.dataimport.pipeline;

import com.rorm.dataimport.source.ImportDataSource;
import com.rorm.metamodel.BasicAttribute;
import com.rorm.metamodel.DataType;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class DataImportPipeline {

    private final JdbcTemplate jdbcTemplate;
    private final JobLauncher jobLauncher;
    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final SchemaGenerator schemaGenerator;

    public ImportResult importData(ImportRequest request) throws Exception {
        createSchema(request.targetSchema());
        createAllTables(request.targetSchema(), request.modelSpace());

        var totalRows = 0L;
        for (var dataSource : request.dataSources()) {
            var columnNames = dataSource.getColumnNames();
            var rowCount = executeImportJob(request, dataSource, columnNames);
            totalRows += rowCount;
        }

        return new ImportResult(request.targetSchema(), request.modelSpace(), totalRows);
    }

    private void createSchema(String schemaName) {
        jdbcTemplate.execute("CREATE SCHEMA IF NOT EXISTS %s".formatted(schemaName));
    }

    private void createAllTables(String schemaName, com.rorm.metamodel.ModelSpace modelSpace) {
        var ddlStatements = schemaGenerator.generateAllTablesDdl(schemaName, modelSpace);
        for (var ddl : ddlStatements) {
            jdbcTemplate.execute(ddl);
        }
    }

    private long executeImportJob(
        ImportRequest request,
        ImportDataSource dataSource,
        List<String> columnNames
    ) throws Exception {
        var reader = new DataSourceItemReader(dataSource);
        var columnTypes = extractColumnTypes(request.modelSpace(), dataSource.getRootName(), columnNames);
        var writer = new DatabaseItemWriter(
            jdbcTemplate,
            request.targetSchema(),
            dataSource.getRootName(),
            columnNames,
            columnTypes
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

    private Map<String, DataType> extractColumnTypes(
        com.rorm.metamodel.ModelSpace modelSpace,
        String rootName,
        List<String> columnNames
    ) {
        var root = modelSpace.roots().stream()
            .filter(r -> r.primaryTableName().equals(rootName))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Root not found: " + rootName));

        var columnTypes = new HashMap<String, DataType>();
        for (var attribute : root.attributes()) {
            if (attribute instanceof BasicAttribute basic) {
                var columnName = basic.location().column();
                if (columnNames.contains(columnName)) {
                    columnTypes.put(columnName, basic.dataType());
                }
            }
        }
        return columnTypes;
    }
}
