package com.rorm.dataimport.pipeline;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ImportPipelineExecutor {

    private final ImportStep<Object, Object> chain;

    @SuppressWarnings("unchecked")
    public ImportPipelineExecutor(List<ImportStep<?, ?>> steps) {
        this.chain = steps.stream()
            .map(s -> (ImportStep<Object, Object>) s)
            .reduce(ImportStep::andThen)
            .orElseThrow(() -> new IllegalStateException("No import steps configured"));
    }

    public ImportResult execute(ImportPipelineRequest request) {
        return (ImportResult) chain.execute(request);
    }
}
