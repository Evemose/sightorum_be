package com.rorm.ai.swarm;

import com.rorm.ai.swarm.SwarmMind.Finding.AnalysisFinding;
import com.rorm.ai.swarm.SwarmMind.Finding.StepFinding;
import com.rorm.ai.swarm.dto.StepExecutionResultDTO;
import com.rorm.ai.swarm.dto.StepRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("SwarmMind")
class SwarmMindTest {

    @Test
    @DisplayName("stores step outputs in memory and vector store")
    void storesStepOutputsInMemoryAndVectorStore() {
        var vectorStore = mock(VectorStore.class);
        var swarmMind = new SwarmMind("swarm-1", vectorStore);
        var stepRef = new StepRef("branch-1", "step-1");
        var stepResult = new StepExecutionResultDTO(
            "summary",
            "insight",
            "details",
            List.of(),
            Map.of("customers", 120),
            Instant.parse("2026-02-21T11:00:00Z")
        );

        swarmMind.storeStep("raw step output", stepRef, stepResult);

        verify(vectorStore, timeout(2_000)).add(any(List.class));
        assertThat(swarmMind.getStepRawOutput(stepRef)).isEqualTo("raw step output");
        assertThat(swarmMind.getStepVariables(stepRef)).isEqualTo(Map.of("customers", 120));
    }

    @Test
    @DisplayName("maps vector-store findings into typed step and analysis findings")
    void mapsVectorStoreFindingsToTypedResults() {
        var vectorStore = mock(VectorStore.class);
        var swarmMind = new SwarmMind("swarm-1", vectorStore);
        var stepRef = new StepRef("branch-1", "step-1");
        var stepResult = new StepExecutionResultDTO(
            "summary",
            "insight",
            "details",
            List.of(),
            Map.of("customers", 120),
            Instant.parse("2026-02-21T11:00:00Z")
        );

        swarmMind.storeStep("raw step output", stepRef, stepResult);
        verify(vectorStore, timeout(2_000)).add(any(List.class));

        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
            new Document(
                "step document",
                Map.of(
                    "granularity", "STEP",
                    "swarmId", "swarm-1",
                    "branchId", "branch-1",
                    "stepId", "step-1"
                )
            ),
            new Document(
                "analysis document",
                Map.of(
                    "granularity", "ANALYSIS",
                    "swarmId", "swarm-1"
                )
            )
        ));

        var searchResult = swarmMind.search(new SwarmMind.SearchQuery("find churn", 2));

        assertThat(searchResult.findings()).hasSize(2);
        assertThat(searchResult.findings().get(0))
            .isInstanceOf(StepFinding.class)
            .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(StepFinding.class))
            .satisfies(finding -> {
                assertThat(finding.content()).contains("step document");
                assertThat(finding.stepRef()).isEqualTo(stepRef);
                assertThat(finding.producedVariables()).isEqualTo(Map.of("customers", 120));
            });

        assertThat(searchResult.findings().get(1))
            .isInstanceOf(AnalysisFinding.class)
            .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(AnalysisFinding.class))
            .satisfies(finding -> assertThat(finding.content()).contains("analysis document"));
    }
}
