package com.rorm.ai.chat.dto;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/**
 * DTO for extracting a structured subconclusion from the AI response.
 * Used to create an {@link com.rorm.ai.chat.node.AgentSubconclusionNode}.
 */
@JsonClassDescription("A structured summary of the agent's analysis and actions taken")
public record SubconclusionDTO(

    @JsonPropertyDescription("""
        A concise 1-2 sentence summary of what was accomplished or discovered.
        Should capture the key insight or result in a way that's useful for context in future interactions.
        Example: "Identified top 10 customers by revenue in Q1 2024, accounting for 45% of total sales."
        """)
    @JsonProperty(required = true)
    String summary,

    @JsonPropertyDescription("""
        The most important insight or conclusion drawn from the analysis.
        This should highlight the key takeaway that the agent wants to emphasize.
        Example: "The majority of high-value customers are in the enterprise segment, indicating a need for targeted marketing."
        """)
    @JsonProperty(required = true)
    String keyInsight,

    @JsonPropertyDescription("""
        A more detailed explanation of the analysis performed and findings.
        Include relevant data points, patterns discovered, and any caveats or limitations.
        This provides the full context for users reviewing the conversation history.
        """)
    @JsonProperty(required = true)
    String details,

    @JsonPropertyDescription("""
        The sequence of research steps taken during this analysis.
        Each step should capture the reasoning, action taken, and what was observed.
        This provides an audit trail of the agent's decision-making process.
        """)
    @JsonProperty(required = true)
    List<AnalysisStep> researchSteps,

    @JsonPropertyDescription("""
        Whether this step requires additional research to complete its objective.
        True if the conclusion is incomplete relative to the step's goal (e.g., partial data,
        inconclusive findings, unanswered sub-questions within scope).
        False if the step's objective is adequately addressed, even if broader questions remain.
        This evaluates completeness of THIS step only, not external dependencies.
        """)
    @JsonProperty(required = true)
    boolean needsFurtherResearch

) {
    /**
     * A single step in the agent's research process.
     */
    @JsonClassDescription("A single step in the research/analysis process")
    public record AnalysisStep(

        @JsonPropertyDescription("""
            The reasoning or hypothesis that led to this action.
            What question were you trying to answer? What did you expect to find?
            """)
        @JsonProperty(required = true)
        String reasoning,

        @JsonPropertyDescription("""
            The specific action taken (e.g., "Executed query to count orders by customer segment").
            Be specific about what tool was used and what parameters were provided.
            """)
        @JsonProperty(required = true)
        String action,

        @JsonPropertyDescription("""
            What was observed or learned from this action.
            Include key data points, unexpected findings, or confirmations of hypotheses.
            """)
        @JsonProperty(required = true)
        String observation

    ) {}
}
