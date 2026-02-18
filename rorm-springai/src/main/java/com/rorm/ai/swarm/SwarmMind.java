package com.rorm.ai.swarm;

import com.rorm.ai.swarm.SwarmMind.Finding.AnalysisFinding;
import com.rorm.ai.swarm.SwarmMind.Finding.StepFinding;
import com.rorm.ai.swarm.dto.StepExecutionResultDTO;
import com.rorm.ai.swarm.dto.StepRef;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Slf4j
@RequiredArgsConstructor
public class SwarmMind {

    private static final String BRANCH_ID = "branchId";
    private static final String STEP_ID = "stepId";
    private static final String GRANULARITY = "granularity";
    private static final String SWARM_ID = "swarmId";

    private final String swarmId;
    private final VectorStore vectorStore;
    private final ConcurrentMap<StepRef, Map<String, Object>> variables = new ConcurrentHashMap<>();
    private final ConcurrentMap<StepRef, String> stepRawOutputs = new ConcurrentHashMap<>();

    public String getStepRawOutput(StepRef stepRef) {
        return stepRawOutputs.get(stepRef);
    }

    public void storeStep(String rawOutput, StepRef ref, StepExecutionResultDTO step) {
        async(() -> {
            variables.put(ref, step.producedVariables());
            stepRawOutputs.put(ref, rawOutput);
            vectorStore.add(List.of(new Document(
                rawOutput,
                Map.of(
                    GRANULARITY, Granularity.STEP.toString(),
                    SWARM_ID, swarmId,
                    STEP_ID, ref.stepId(),
                    BRANCH_ID, ref.branchId(),
                    "timestamp", step.completedAt().toString()
                )
            )));
        });
    }

    private void async(Runnable task) {
        Thread.ofVirtual().start(() -> {
            try {
                task.run();
            } catch (Exception e) {
                log.error("Error in async task", e);
            }
        });
    }

    public void storeAnalysis(String rawOutput) {
        async(() -> vectorStore.add(List.of(new Document(
            rawOutput,
            Map.of(
                GRANULARITY, Granularity.ANALYSIS,
                SWARM_ID, swarmId
            )
        ))));
    }

    public QuestionAnswerAdvisor stepAdvisor() {
        return QuestionAnswerAdvisor
            .builder(vectorStore)
            .searchRequest(
                SearchRequest.builder()
                    .similarityThreshold(0.8d)
                    .topK(3)
                    .filterExpression(GRANULARITY + " == 'step' && " + SWARM_ID + " == '" + swarmId + "'")
                    .build()
            )
            .build();
    }

    public SearchResult search(SearchQuery query) {
        var results = vectorStore.similaritySearch(
            SearchRequest.builder()
                .query(query.query())
                .similarityThreshold(0.8d)
                .topK(query.maxResults())
                .filterExpression(SWARM_ID + " == '" + swarmId + "'")
                .build()
        );
        var findings = results.stream()
            .<Finding>map(doc -> switch (Granularity.valueOf(doc.getMetadata().get(GRANULARITY).toString())) {
                case STEP -> {
                    var stepRef = new StepRef(
                        doc.getMetadata().get(BRANCH_ID).toString(),
                        doc.getMetadata().get(STEP_ID).toString()
                    );
                    yield new StepFinding(
                        doc.getFormattedContent(),
                        Granularity.STEP,
                        stepRef,
                        variables.getOrDefault(stepRef, Map.of())
                    );
                }
                case ANALYSIS -> new AnalysisFinding(
                    doc.getFormattedContent(),
                    Granularity.ANALYSIS
                );
            })
            .toList();
        return new SearchResult(findings);
    }

    public Map<String, Object> getStepVariables(StepRef step) {
        return variables.getOrDefault(step, Map.of());
    }

    public enum Granularity {
        STEP, ANALYSIS
    }

    public sealed interface Finding permits StepFinding, AnalysisFinding {
        String content();

        Granularity granularity();

        record StepFinding(
            String content,
            Granularity granularity,
            StepRef stepRef,
            Map<String, Object> producedVariables
        ) implements Finding {}

        record AnalysisFinding(String content, Granularity granularity) implements Finding {}
    }

    public record SearchQuery(
        String query,
        int maxResults
    ) {
        public SearchQuery {
            if (maxResults <= 0) {
                maxResults = 3;
            }
        }
    }

    public record SearchResult(List<Finding> findings) {}

}
