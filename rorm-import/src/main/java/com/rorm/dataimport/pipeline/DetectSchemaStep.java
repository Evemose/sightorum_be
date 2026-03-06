package com.rorm.dataimport.pipeline;

import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(2)
@RequiredArgsConstructor
public class DetectSchemaStep implements ImportStep<LoadedSources, DetectedSources> {

    private final ModelSpaceDetector modelSpaceDetector;

    @Override
    public DetectedSources execute(LoadedSources input) {
        var request = input.request();

        var detectedSchema = modelSpaceDetector.detect(
            input.dataSources(),
            request.overridesByRoot(),
            request.listSeparator()
        );

        return new DetectedSources(request, input.dataSources(), detectedSchema, request.coercionStrategies());
    }
}
