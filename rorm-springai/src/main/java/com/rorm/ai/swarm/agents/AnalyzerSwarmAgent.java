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
import java.util.Map;

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
        Map<String, BranchExecutionResultDTO> branchResults,
        Many<SwarmEvent> eventSink
    ) {
        var tokenSink = createTokenSink();
        var id = "analysis";
        eventSink.tryEmitNext(new AnalysisNegotiationStarted(id, tokenSink.asFlux()));

        var scores = new ArrayList<Double>();

        for (var iteration = 0; ; iteration++) {
            tokenSink.tryEmitNext("Iteration " + (iteration + 1) + ":\nAnalyzer:\n\n");

            var analysis = createAnalysis(originalQuery, branchResults, tokenSink, eventSink, iteration);

            tokenSink.tryEmitNext("\nCritic:\n\n");
            var critique = critiqueAnalysis(analysis, originalQuery, branchResults, tokenSink, eventSink, iteration);
            scores.add(critique.conclusionScore());

            var finishReason = NegotiationBreaker.shouldFinish(scores, iteration + 1);
            if (finishReason.isPresent()) {
                eventSink.tryEmitNext(new AnalysisNegotiationFinished(
                    id,
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
        Map<String, BranchExecutionResultDTO> branchResults,
        Many<String> tokenSink,
        Many<SwarmEvent> eventSink,
        int iteration
    ) {
        var userPrompt = buildAnalysisPrompt(originalQuery, branchResults);

        return streamAndStructurize(
            StepParams.<AnalysisResultDTO>builder()
                .agent(analyzer)
                .userPrompt(userPrompt)
                .responseType(AnalysisResultDTO.class)
                .eventId("analysis")
                .startEventFactory((id, tokens) ->
                    new AnalysisVersionCreationStarted(id, iteration, tokens)
                )
                .endEventFactory((id, version, raw) ->
                    new AnalysisVersionCreationFinished(id, iteration, version, raw)
                )
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
        Map<String, BranchExecutionResultDTO> branchResults,
        Many<String> tokenSink,
        Many<SwarmEvent> eventSink,
        int iteration
    ) {
        return streamAndStructurize(
            StepParams.<ConclusionCritiqueDTO>builder()
                .agent(critic)
                .userPrompt(
                    buildAnalysisPrompt(originalQuery, branchResults)
                    + "\n\nAnalysis to critique:\n\n" +
                    buildAnalysisResult(analysis)
                )
                .responseType(ConclusionCritiqueDTO.class)
                .eventId("analysis")
                .startEventFactory((id, tokens) ->
                    new AnalysisVersionCritiqueStarted(id, iteration, tokens)
                )
                .endEventFactory((id, version, raw) ->
                    new AnalysisVersionCritiqueFinished(id, iteration, version, raw)
                )
                .tokenSink(tokenSink)
                .requestBuilderCustomizer(b ->
                    b.withAdvisor(swarmMind.stepAdvisor()).withTool(new SwarmMindTool(swarmMind))
                )
                .build(),
            eventSink
        );
    }

    private String buildAnalysisPrompt(String originalQuery, Map<String, BranchExecutionResultDTO> branchResults) {
        var prompt = new StringBuilder(originalQuery);
        prompt.append("\n\nBranch Findings:\n\n");

        for (var entry : branchResults.entrySet()) {
            var branch = entry.getValue();
            prompt.append("Branch: ").append(entry.getKey()).append("\n");
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

    private String buildAnalysisResult(AnalysisResultDTO analysis) {
        var result = new StringBuilder();
        result.append("Main Conclusion:\n").append(analysis.mainConclusion()).append("\n\n");
        result.append("Supporting Evidence:\n");
        for (var evidence : analysis.supportingEvidence()) {
            result.append("- [").append(evidence.strength()).append("] ")
                .append(evidence.sourceBranch()).append(": ").append(evidence.finding()).append("\n");
        }
        result.append("\nConfidence: ").append(analysis.confidence()).append("\n\n");
        result.append("Alternative Interpretations:\n");
        for (var alt : analysis.alternatives()) {
            result.append("- ").append(alt).append("\n");
        }
        result.append("\nGaps and Limitations:\n");
        for (var gap : analysis.gaps()) {
            result.append("- [").append(gap.impact()).append("] ").append(gap.description());
            if (gap.requiredData() != null) {
                result.append(" (Needs: ").append(gap.requiredData()).append(")");
            }
            result.append("\n");
        }
        if (analysis.needsMoreResearch()) {
            result.append("\nRecommended Follow-Up Research:\n");
            for (var followUp : analysis.suggestedFollowUp()) {
                result.append("- ").append(followUp).append("\n");
            }
        }
        return result.toString();
    }
}
