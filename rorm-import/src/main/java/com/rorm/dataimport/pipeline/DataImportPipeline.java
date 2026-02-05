package com.rorm.dataimport.pipeline;

import com.rorm.dataimport.source.ImportDataSource;
import com.rorm.metamodel.ModelSpace;
import com.rorm.metamodel.Root;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.support.SynchronizedItemReader;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@RequiredArgsConstructor
public class DataImportPipeline {

    private final JdbcTemplate jdbcTemplate;
    private final JobLauncher jobLauncher;
    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final SchemaGenerator schemaGenerator;
    private final TaskExecutor taskExecutor;
    private final MetamodelConverter metamodelConverter;
    private final TransactionTemplate transactionTemplate;

    public ImportResult importData(ImportRequest request) throws Exception {
        var box = new Object() {
            @SuppressWarnings("NotNullFieldNotInitialized")
            ModelSpace modelSpace;
        };
        transactionTemplate.executeWithoutResult(_ -> {
            createSchema(request.targetSchema());
            box.modelSpace = metamodelConverter.convertToModelSpace(request.detectedSchema());
            createAllTables(request.targetSchema(), box.modelSpace);
        });

        try {
            var modelSpace = box.modelSpace;
            var totalRows = 0L;
            for (var dataSource : request.dataSources()) {
                var rowCount = executeImportJob(request, modelSpace, dataSource);
                totalRows += rowCount;
            }

            return new ImportResult(request.targetSchema(), modelSpace, totalRows);
        } catch (Exception e) {
            //noinspection SqlSourceToSinkFlow
            jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + quoteIdentifier(request.targetSchema()) + " CASCADE");
            throw e;
        }
    }

    private void createSchema(String schemaName) {
        jdbcTemplate.execute("CREATE SCHEMA IF NOT EXISTS " + quoteIdentifier(schemaName));
    }

    private void createAllTables(String schemaName, ModelSpace modelSpace) {
        var ddlStatements = schemaGenerator.generateAllTablesDdl(schemaName, modelSpace);
        for (var ddl : ddlStatements) {
            jdbcTemplate.execute(ddl);
        }
    }

    protected long executeImportJob(ImportRequest request, ModelSpace modelSpace, ImportDataSource dataSource) throws Exception {
        var reader = new DataSourceItemReader(dataSource);
        var writer = createWriterForDataSource(request, modelSpace, dataSource);

        var step = new StepBuilder("import-" + dataSource.getRootName(), jobRepository)
            .<Map<String, Object>, Map<String, Object>>chunk(request.chunkSize(), transactionManager)
            .reader(new SynchronizedItemReader<>(reader))
            .writer(writer)
            .taskExecutor(taskExecutor)
            .listener(new ProgressLoggingListener())
            .build();

        var jobName = "import-job-" + dataSource.getRootName() + "-" + UUID.randomUUID();
        var job = new JobBuilder(jobName, jobRepository)
            .start(step)
            .build();

        var execution = jobLauncher.run(job, new JobParameters());

        // Check if the job failed and propagate any exceptions
        if (execution.getStatus().isUnsuccessful()) {
            var failureExceptions = execution.getAllFailureExceptions();
            if (!failureExceptions.isEmpty()) {
                var rootCause = failureExceptions.getFirst();
                if (rootCause instanceof RuntimeException re) {
                    throw re;
                }
                throw new RuntimeException("Import job failed", rootCause);
            }
            throw new RuntimeException("Import job failed with status: " + execution.getStatus());
        }

        return execution.getStepExecutions().stream()
            .mapToLong(StepExecution::getWriteCount)
            .sum();
    }

    private String quoteIdentifier(String identifier) {
        // Escape any existing double quotes and wrap in quotes
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    /**
     * Creates an appropriate writer for the given datasource.
     * Finds all roots that source from this datasource (including implicit one-to-one roots)
     * and creates a multi-root writer if needed.
     */
    private ItemWriter<Map<String, Object>> createWriterForDataSource(
        ImportRequest request,
        ModelSpace modelSpace,
        ImportDataSource dataSource
    ) {
        var detectedSchema = request.detectedSchema();
        var dataSourceName = dataSource.getRootName();

        // Find all roots that source from this datasource
        var rootsFromThisSource = detectedSchema.roots().values().stream()
            .filter(r -> dataSourceName.equals(r.sourceDataSource()))
            .toList();

        if (rootsFromThisSource.size() > 1) {
            // Multiple roots from this datasource - use multi-root writer
            var rootMap = modelSpace.roots().stream()
                .collect(Collectors.toMap(Root::primaryTableName, r -> r));

            return new MultiRootItemWriter(
                jdbcTemplate,
                request.targetSchema(),
                rootsFromThisSource,
                rootMap
            );
        } else if (rootsFromThisSource.size() == 1) {
            // Single root
            var detectedRoot = rootsFromThisSource.getFirst();
            var root = findRoot(modelSpace, detectedRoot.name());
            var mappingBuilder = new ColumnMappingBuilder();
            var columnMappings = mappingBuilder.buildMappings(detectedRoot);

            return new DatabaseItemWriter(
                jdbcTemplate,
                request.targetSchema(),
                detectedRoot.name(),
                root.idDescriptor(),
                columnMappings
            );
        } else {
            throw new IllegalStateException("No roots found for datasource: " + dataSourceName);
        }
    }

    private Root findRoot(ModelSpace modelSpace, String rootName) {
        return modelSpace.roots().stream()
            .filter(r -> r.primaryTableName().equals(rootName))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Root not found: " + rootName));
    }

}
