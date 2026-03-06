package com.rorm.dataimport.pipeline;

import com.rorm.dataimport.source.ImportDataSource;
import com.rorm.dataimport.type.InMemoryCoercion;
import com.rorm.dataimport.type.InvalidValueCoercionStrategy;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class BuildImportRequestStepTest {

    private final BuildImportRequestStep step = new BuildImportRequestStep();

    @Test
    void buildsRequestFromDetectedSources() {
        var request = new ImportPipelineRequest(
            Path.of("/tmp/upload"), "test_schema", 500, ",", Map.of(), Map.of()
        );
        var dataSources = List.of(mock(ImportDataSource.class));
        var schema = new DetectedSchema(Map.of());
        Map<ImportRequest.AttributeKey, InvalidValueCoercionStrategy> coercions = Map.of(
            new ImportRequest.AttributeKey("root", "col"), InMemoryCoercion.Skip.INSTANCE
        );

        var input = new DetectedSources(request, dataSources, schema, coercions);
        var result = step.execute(input);

        assertThat(result.targetSchema()).isEqualTo("test_schema");
        assertThat(result.dataSources()).isEqualTo(dataSources);
        assertThat(result.detectedSchema()).isEqualTo(schema);
        assertThat(result.chunkSize()).isEqualTo(500);
        assertThat(result.coercionStrategies()).isEqualTo(coercions);
    }

    @Test
    void defaultChunkSizeWhenZero() {
        var request = new ImportPipelineRequest(
            Path.of("/tmp/upload"), "schema", 0, ",", Map.of(), Map.of()
        );
        var input = new DetectedSources(request, List.of(), new DetectedSchema(Map.of()), Map.of());

        var result = step.execute(input);

        assertThat(result.chunkSize()).isEqualTo(1000);
    }
}
