package com.rorm.dataimport.pipeline;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(3)
public class BuildImportRequestStep implements ImportStep<DetectedSources, ImportRequest> {

    @Override
    public ImportRequest execute(DetectedSources input) {
        return new ImportRequest(
            input.request().targetSchema(),
            input.dataSources(),
            input.schema(),
            input.request().chunkSize(),
            input.coercions()
        );
    }
}
