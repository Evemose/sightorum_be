package com.rorm.dataimport.pipeline;

import com.rorm.dataimport.pipeline.listeners.ImportEventListener;
import com.rorm.dataimport.pipeline.listeners.ProgressLoggingListener;
import com.rorm.dataimport.source.ImportDataSource;
import com.rorm.metamodel.ModelSpace;
import com.rorm.metamodel.Root;
import org.springframework.batch.core.ChunkListener;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.support.SynchronizedItemReader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@Component
public class BatchJobLauncher {

    private final JdbcTemplate jdbcTemplate;
    private final JobLauncher jobLauncher;
    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final Executor executor = Executors.newVirtualThreadPerTaskExecutor();

    public BatchJobLauncher(
        JdbcTemplate jdbcTemplate,
        JobLauncher jobLauncher,
        JobRepository jobRepository,
        PlatformTransactionManager transactionManager
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.jobLauncher = jobLauncher;
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
    }

    public List<Flux<ImportEvent>> launchAll(PreparedImport prepared) {
        var request = prepared.request();
        var modelSpace = prepared.modelSpace();
        return request.dataSources().stream()
            .map(ds -> launchForDataSource(request, modelSpace, ds))
            .toList();
    }

    protected Flux<ImportEvent> launchForDataSource(ImportRequest request, ModelSpace modelSpace, ImportDataSource dataSource) {
        var reader = new DataSourceItemReader(dataSource);
        var writer = createWriterForDataSource(request, modelSpace, dataSource);
        var sink = Sinks.many().multicast().<ImportEvent>onBackpressureBuffer();

        var step = buildStep(dataSource.getRootName(), reader, writer, request.chunkSize(), sink);

        var jobName = "import-job-" + dataSource.getRootName() + "-" + UUID.randomUUID();
        var job = new JobBuilder(jobName, jobRepository)
            .start(step)
            .build();

        runJobToSink(job, sink);
        return sink.asFlux();
    }

    private ItemWriter<Map<String, Object>> createWriterForDataSource(
        ImportRequest request,
        ModelSpace modelSpace,
        ImportDataSource dataSource
    ) {
        var detectedSchema = request.detectedSchema();
        var dataSourceName = dataSource.getRootName();

        var rootsFromThisSource = detectedSchema.roots().values().stream()
            .filter(r -> dataSourceName.equals(r.sourceDataSource()))
            .toList();

        if (rootsFromThisSource.size() > 1) {
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

    private org.springframework.batch.core.Step buildStep(
        String rootName,
        DataSourceItemReader reader,
        ItemWriter<Map<String, Object>> writer,
        int chunkSize,
        Sinks.Many<ImportEvent> sink
    ) {
        var stepBuilder = new StepBuilder("import-" + rootName, jobRepository)
            .<Map<String, Object>, Map<String, Object>>chunk(chunkSize, transactionManager)
            .reader(new SynchronizedItemReader<>(reader))
            .writer(writer)
            .listener(new ProgressLoggingListener())
            .listener(new ImportEventListener(sink));

        if (writer instanceof ChunkListener chunkListener) {
            stepBuilder.listener(chunkListener);
        }

        return stepBuilder.build();
    }

    private void runJobToSink(org.springframework.batch.core.Job job, Sinks.Many<ImportEvent> sink) {
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
    }

    private Root findRoot(ModelSpace modelSpace, String rootName) {
        return modelSpace.roots().stream()
            .filter(r -> r.primaryTableName().equals(rootName))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Root not found: " + rootName));
    }
}
