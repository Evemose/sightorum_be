package com.rorm.ai.swarm.agents;

import com.rorm.ai.swarm.SwarmEvent;
import com.rorm.ai.swarm.SwarmEvent.*;
import com.rorm.ai.swarm.SwarmMind;
import com.rorm.ai.swarm.SwarmMindTool;
import com.rorm.ai.swarm.dto.AnalysisResultDTO;
import com.rorm.ai.swarm.dto.BranchExecutionResultDTO;
import com.rorm.ai.swarm.dto.ConclusionCritiqueDTO;
import reactor.core.publisher.Sinks.Many;

import java.util.ArrayList;
import java.util.List;

/**
 * Analyzer agent with critic negotiation loop
 */
public class AnalyzerSwarmAgent extends SwarmAgent {

    private final SwarmMind swarmMind;
    private final FirstLevelSwarmAgent analyzer;
    private final FirstLevelSwarmAgent critic;

    public AnalyzerSwarmAgent(
        SwarmMind swarmMind,
        FirstLevelSwarmAgent analyzer,
        FirstLevelSwarmAgent critic,
        SecondarySwarmAgent summarizer
    ) {
        super(summarizer);
        this.swarmMind = swarmMind;
        this.analyzer = analyzer;
        this.critic = critic;
    }

    public AnalysisResultDTO negotiate(
        String originalQuery,
        List<BranchExecutionResultDTO> branchResults,
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

            var finishReason = NegotiationBreaker.shouldFinish(scores, iteration + 1);
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
        List<BranchExecutionResultDTO> branchResults,
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
                .requestBuilderCustomizer(b ->
                    b.withAdvisor(swarmMind.stepAdvisor()).withTool(new SwarmMindTool(swarmMind))
                )
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
                .requestBuilderCustomizer(b ->
                    b.withAdvisor(swarmMind.stepAdvisor()).withTool(new SwarmMindTool(swarmMind))
                )
                .build(),
            eventSink
        );
    }

    private String buildAnalysisPrompt(String originalQuery, List<BranchExecutionResultDTO> branchResults) {
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
}