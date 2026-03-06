package com.rorm.dataimport.pipeline;

import com.rorm.dataimport.pipeline.profile.SchemaAnalyzer;
import com.rorm.dataimport.pipeline.profile.SchemaProfileStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Slf4j
@Component
@Order(6)
@RequiredArgsConstructor
public class AnalyzeSchemaStep implements ImportStep<ImportResult, ImportResult> {

    private final SchemaAnalyzer analyzer;
    private final SchemaProfileStore profileStore;

    @Override
    public ImportResult execute(ImportResult result) {
        result.progress()
            .ignoreElements()
            .then(Mono.fromRunnable(() -> runAnalysis(result)))
            .subscribeOn(Schedulers.boundedElastic())
            .subscribe(
                null,
                e -> log.error("Schema analysis failed for '{}'", result.targetSchema(), e)
            );
        return result;
    }

    private void runAnalysis(ImportResult result) {
        log.info("Starting schema analysis for '{}'", result.targetSchema());
        var profile = analyzer.analyze(result.targetSchema(), result.modelSpace());
        profileStore.store(result.targetSchema(), profile);
        log.info("Schema analysis complete for '{}': {} entities profiled",
            result.targetSchema(), profile.entities().size());
    }
}
