package com.rorm.dataimport.pipeline;

import com.rorm.dataimport.source.ImportDataSource;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Component
@Order(5)
@RequiredArgsConstructor
public class RunBatchImportStep implements ImportStep<PreparedImport, ImportResult> {

    private final BatchJobLauncher batchJobLauncher;
    private final DbCoercionExecutor dbCoercionExecutor;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public ImportResult execute(PreparedImport prepared) {
        var request = prepared.request();

        var totalRows = request.dataSources().stream()
            .mapToLong(ImportDataSource::countRows)
            .filter(c -> c >= 0)
            .sum();

        var eventFluxes = batchJobLauncher.launchAll(prepared);

        var progress = Flux.merge(eventFluxes)
            .concatWith(dbCoercionExecutor.asFlux(prepared))
            .onErrorResume(e ->
                Mono.<ImportEvent>fromRunnable(() -> dropSchema(request.targetSchema()))
                    .subscribeOn(Schedulers.boundedElastic())
                    .then(Mono.<ImportEvent>error(e))
                    .flux()
            )
            .scan(ImportProgress.initial(totalRows), ImportProgress::append)
            .skip(1)
            .cache();

        return new ImportResult(request.targetSchema(), prepared.modelSpace(), progress);
    }

    private void dropSchema(String schemaName) {
        //noinspection SqlSourceToSinkFlow
        jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + quoteIdentifier(schemaName) + " CASCADE");
    }

    private String quoteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
