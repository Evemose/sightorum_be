package com.rorm.dataimport.pipeline;

import com.rorm.dataimport.source.ImportDataSource;
import com.rorm.metamodel.ModelSpace;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class ImportPipelineExecutorTest {

    @Test
    void chainsAllStepsInOrder() {
        var pipelineRequest = new ImportPipelineRequest(
            Path.of("/tmp"), "schema", 1000, ",", Map.of(), Map.of()
        );
        var dataSources = List.of(mock(ImportDataSource.class));
        var loaded = new LoadedSources(pipelineRequest, dataSources);
        var detected = new DetectedSources(pipelineRequest, dataSources, new DetectedSchema(Map.of()), Map.of());
        var importRequest = new ImportRequest("schema", dataSources, new DetectedSchema(Map.of()));
        var prepared = new PreparedImport(importRequest, mock(ModelSpace.class));
        var expectedResult = new ImportResult("schema", mock(ModelSpace.class), Flux.empty());

        ImportStep<ImportPipelineRequest, LoadedSources> step1 = _ -> loaded;
        ImportStep<LoadedSources, DetectedSources> step2 = _ -> detected;
        ImportStep<DetectedSources, ImportRequest> step3 = _ -> importRequest;
        ImportStep<ImportRequest, PreparedImport> step4 = _ -> prepared;
        ImportStep<PreparedImport, ImportResult> step5 = _ -> expectedResult;

        var executor = new ImportPipelineExecutor(List.of(step1, step2, step3, step4, step5));

        var actual = executor.execute(pipelineRequest);
        assertThat(actual).isSameAs(expectedResult);
    }

    @Test
    void propagatesExceptionFromStep() {
        ImportStep<ImportPipelineRequest, LoadedSources> failing = _ -> {
            throw new IllegalStateException("load failed");
        };

        var executor = new ImportPipelineExecutor(List.of(failing));

        var request = new ImportPipelineRequest(
            Path.of("/tmp"), "schema", 1000, ",", Map.of(), Map.of()
        );

        assertThatThrownBy(() -> executor.execute(request))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("load failed");
    }

    @Test
    void emptyStepsThrows() {
        assertThatThrownBy(() -> new ImportPipelineExecutor(List.of()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("No import steps configured");
    }
}
