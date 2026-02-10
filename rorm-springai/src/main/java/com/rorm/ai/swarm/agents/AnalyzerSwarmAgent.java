package com.rorm.ai.swarm.agents;

import com.rorm.ai.swarm.NegotiationFinishReason;
import com.rorm.ai.swarm.SwarmEvent;
import com.rorm.ai.swarm.SwarmEvent.*;
import com.rorm.ai.swarm.dto.AnalysisResultDTO;
import com.rorm.ai.swarm.dto.BranchExecutionResult;
import com.rorm.ai.swarm.dto.ConclusionCritiqueDTO;
import reactor.core.publisher.Sinks.Many;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Analyzer agent with critic negotiation loop
 */
public class AnalyzerSwarmAgent extends SwarmAgent {
    private final FirstLevelSwarmAgent analyzer;
    private final FirstLevelSwarmAgent critic;

    public AnalyzerSwarmAgent(
        FirstLevelSwarmAgent analyzer,
        FirstLevelSwarmAgent critic,
        SecondarySwarmAgent summarizer
    ) {
        super(summarizer);
        this.analyzer = analyzer;
        this.critic = critic;
    }

    public AnalysisResultDTO negotiate(
        String originalQuery,
        List<BranchExecutionResult> branchResults,
        Many<SwarmEvent> eventSink
    ) {
        var tokenSink = createTokenSink();
        eventSink.tryEmitNext(new AnalysisNegotiationStarted(tokenSink.asFlux()));

        var scores = new ArrayList<Double>();

        for (var iteration = 0; ; iteration++) {
            tokenSink.tryEmitNext("Iteration " + (iteration + 1) + ":\nAnalyzer:\n\n");

            var analysis = createAnalysis(originalQuery, branchResults, tokenSink, eventSink);

            tokenSink.tryEmitNext("\nCritic:\n\n");
            var critique = critiqueAnalysis(analysis, originalQuery, tokenSink, eventSink);
            scores.add(critique.conclusionScore());

            var finishReason = shouldFinish(scores, iteration + 1);
            if (finishReason.isPresent()) {
                eventSink.tryEmitNext(new AnalysisNegotiationFinished(
                    analysis,
                    "Analysis negotiation completed",
                    finishReason.get()
                ));
                tokenSink.tryEmitComplete();
                return analysis;
            }
        }
    }

    private AnalysisResultDTO createAnalysis(
        String originalQuery,
        List<BranchExecutionResult> branchResults,
        Many<String> tokenSink,
        Many<SwarmEvent> eventSink
    ) {
        var userPrompt = buildAnalysisPrompt(originalQuery, branchResults);

        return streamAndStructurize(
            StepParams.<AnalysisResultDTO>builder()
                .agent(analyzer)
                .userPrompt(userPrompt)
                .responseType(AnalysisResultDTO.class)
                .startEventFactory(AnalysisVersionCreationStarted::new)
                .endEventFactory(AnalysisVersionCreationFinished::new)
                .tokenSink(tokenSink)
                .build(),
            eventSink
        );
    }

    private ConclusionCritiqueDTO critiqueAnalysis(
        AnalysisResultDTO analysis,
        String originalQuery,
        Many<String> tokenSink,
        Many<SwarmEvent> eventSink
    ) {
        return streamAndStructurize(
            StepParams.<ConclusionCritiqueDTO>builder()
                .agent(critic)
                .userPrompt(originalQuery + "\n\nAnalysis:\n" + analysis)
                .responseType(ConclusionCritiqueDTO.class)
                .startEventFactory(AnalysisVersionCritiqueStarted::new)
                .endEventFactory(AnalysisVersionCritiqueFinished::new)
                .tokenSink(tokenSink)
                .build(),
            eventSink
        );
    }

    private Optional<NegotiationFinishReason> shouldFinish(List<Double> scores, int iteration) {
        var hardLimit = 5;
        var softLimit = 3;

        if (iteration >= hardLimit) {
            return Optional.of(NegotiationFinishReason.MAX_ITERATIONS_REACHED);
        }

        double threshold = 8.5 - 0.75 * iteration;
        if (scores.getLast() >= threshold && iteration < softLimit) {
            return Optional.of(NegotiationFinishReason.APPROVED);
        }

        if (recentVsOverallAvg(scores, iteration) < 0.3) {
            return Optional.of(NegotiationFinishReason.INSIGNIFICANT_IMPROVEMENT);
        }

        return Optional.empty();
    }

    private String buildAnalysisPrompt(String originalQuery, List<BranchExecutionResult> branchResults) {
        var prompt = new StringBuilder(originalQuery);
        prompt.append("\n\nBranch Findings:\n\n");

        for (var branch : branchResults) {
            prompt.append("Branch: ").append(branch.branchId()).append("\n");
            prompt.append("Goal: ").append(branch.goal()).append("\n");
            prompt.append("Summary: ").append(branch.branchSummary()).append("\n");
            prompt.append("Key Insights:\n");
            for (var insight : branch.keyInsights()) {
                prompt.append("  - ").append(insight).append("\n");
            }
            prompt.append("\n");
        }

        return prompt.toString();
    }

    private double recentVsOverallAvg(List<Double> scores, int iteration) {
        if (iteration < 2) {
            return 1.0;
        }

        var recentAvg = (scores.get(iteration - 1) + scores.get(iteration)) / 2.0;
        var overallAvg = scores.stream().limit(iteration + 1).mapToDouble(Double::doubleValue).average().orElse(0);

        return recentAvg - overallAvg;
    }
}