package com.rorm.dataimport.pipeline;

import com.rorm.dataimport.attribute.DetectedAttribute;
import com.rorm.dataimport.pipeline.listeners.ImportEventListener;
import com.rorm.dataimport.pipeline.listeners.ProgressLoggingListener;
import com.rorm.dataimport.source.ImportDataSource;
import com.rorm.dataimport.type.DbLevelCoercion;
import com.rorm.metamodel.ModelSpace;
import com.rorm.metamodel.Root;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.batch.core.ChunkListener;
import org.springframework.batch.core.JobParameters;
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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
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
    private final Executor executor = Executors.newVirtualThreadPerTaskExecutor();

    public ImportResult importData(ImportRequest request) {

        var box = new Object() {
            ModelSpace modelSpace;
        };
        transactionTemplate.executeWithoutResult(_ -> {
            createSchema(request.targetSchema());
            box.modelSpace = metamodelConverter.convertToModelSpace(request.detectedSchema());
            createAllTables(request.targetSchema(), box.modelSpace);
        });
        var modelSpace = box.modelSpace;

        // Count total rows across all data sources for progress tracking
        var totalRows = request.dataSources().stream()
            .mapToLong(ImportDataSource::countRows)
            .filter(c -> c >= 0)
            .sum();

        // Each executeImportJob launches in the background and returns a live Flux
        var eventFluxes = request.dataSources().stream()
            .map(ds -> executeImportJob(request, modelSpace, ds))
            .toList();

        var progress = Flux.merge(eventFluxes)
            .concatWith(Flux.defer(() ->
                Mono.<ImportEvent>fromRunnable(() -> executeDbLevelCoercions(request, modelSpace))
                    .subscribeOn(Schedulers.boundedElastic())
                    .flux()
            ))
            .onErrorResume(e ->
                Mono.<ImportEvent>fromRunnable(() -> {
                        //noinspection SqlSourceToSinkFlow
                        jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + quoteIdentifier(request.targetSchema()) + " CASCADE");
                    })
                    .subscribeOn(Schedulers.boundedElastic())
                    .then(Mono.<ImportEvent>error(e))
                    .flux()
            )
            .scan(ImportProgress.initial(totalRows), ImportProgress::append)
            .skip(1)
            .cache();

        return new ImportResult(request.targetSchema(), modelSpace, progress);
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

    protected Flux<ImportEvent> executeImportJob(ImportRequest request, ModelSpace modelSpace, ImportDataSource dataSource) {
        var reader = new DataSourceItemReader(dataSource);
        var writer = createWriterForDataSource(request, modelSpace, dataSource);
        var sink = Sinks.many().multicast().<ImportEvent>onBackpressureBuffer();

        var stepBuilder = new StepBuilder("import-" + dataSource.getRootName(), jobRepository)
            .<Map<String, Object>, Map<String, Object>>chunk(request.chunkSize(), transactionManager)
            .reader(new SynchronizedItemReader<>(reader))
            .writer(writer)
            .taskExecutor(taskExecutor)
            .listener(new ProgressLoggingListener())
            .listener(new ImportEventListener(sink));

        if (writer instanceof ChunkListener chunkListener) {
            stepBuilder.listener(chunkListener);
        }

        var step = stepBuilder.build();

        var jobName = "import-job-" + dataSource.getRootName() + "-" + UUID.randomUUID();
        var job = new JobBuilder(jobName, jobRepository)
            .start(step)
            .build();

        CompletableFuture.runAsync(() -> {
            try {
                var execution = jobLauncher.run(job, new JobParameters());

                if (execution.getStatus().isUnsuccessful()) {
                    var failureExceptions = execution.getAllFailureExceptions();
                    if (!failureExceptions.isEmpty()) {
                        sink.tryEmitError(failureExceptions.getFirst());
                    } else {
                        sink.tryEmitError(new RuntimeException("Import job failed with status: " + execution.getStatus()));
                    }
                } else {
                    sink.tryEmitComplete();
                }
            } catch (Exception e) {
                sink.tryEmitError(e);
            }
        }, executor);

        return sink.asFlux();
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
                rootMap,
                request.coercionStrategies()
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
                columnMappings,
                request.coercionStrategies()
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

    /**
     * Execute database-level coercions (ForwardFill, BackwardFill, UseMean, UseMedian, UseMode).
     * These run as SQL UPDATE statements after all data has been imported.
     */
    private void executeDbLevelCoercions(ImportRequest request, ModelSpace modelSpace) {
        var dbCoercions = request.coercionStrategies().entrySet().stream()
            .filter(e -> e.getValue() instanceof DbLevelCoercion)
            .toList();

        if (dbCoercions.isEmpty()) {
            return;
        }

        transactionTemplate.executeWithoutResult(_ -> {
            for (var entry : dbCoercions) {
                var key = entry.getKey();
                var strategy = (DbLevelCoercion) entry.getValue();

                // Find the root and attribute to get metadata
                var detectedRoot = request.detectedSchema().roots().get(key.rootName());
                if (detectedRoot == null) {
                    throw new IllegalStateException("Root not found: " + key.rootName());
                }

                var attribute = findAttributeByPath(detectedRoot.attributes(), key.attributePath());
                if (attribute == null) {
                    throw new IllegalStateException("Attribute not found: " + key.attributePath());
                }

                if (!(attribute instanceof DetectedAttribute.Basic basicAttr)) {
                    throw new IllegalStateException("DbLevelCoercion only applicable to basic attributes, not: " + attribute.getClass().getSimpleName());
                }

                // Find the root from modelSpace to get the ID column name and actual dataType
                var root = findRoot(modelSpace, key.rootName());
                var idColumnName = root.idDescriptor().columnName();

                // Get the table and column names
                var tableName = key.rootName();
                var columnName = basicAttr.name();

                // Get the actual data type from the metamodel (more reliable than detected type)
                var metamodelAttr = root.attributes().stream()
                    .filter(a -> a instanceof com.rorm.metamodel.BasicAttribute)
                    .map(a -> (com.rorm.metamodel.BasicAttribute) a)
                    .filter(a -> a.location().column().equals(columnName))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Attribute not found in metamodel: " + columnName));
                var dataType = metamodelAttr.dataType();

                // Generate and execute SQL
                var sql = strategy.generateSql(
                    request.targetSchema(),
                    tableName,
                    columnName,
                    dataType,
                    idColumnName
                );

                jdbcTemplate.execute(sql);
            }
        });
    }

    private @Nullable DetectedAttribute findAttributeByPath(Map<String, DetectedAttribute> attributes, String path) {
        if (!path.contains(".")) {
            return attributes.get(path);
        }
        var parts = path.split("\\.", 2);
        var first = attributes.get(parts[0]);
        if (first instanceof DetectedAttribute.Composite composite) {
            return findAttributeByPath(composite.subAttributes(), parts[1]);
        }
        return null;
    }

}
