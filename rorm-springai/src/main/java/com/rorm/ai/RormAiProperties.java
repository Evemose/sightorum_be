package com.rorm.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rorm.ai")
public record RormAiProperties(
    String systemPrompt,
    int maxQueryResults
) {
    public RormAiProperties {
        if (systemPrompt == null || systemPrompt.isBlank()) {
            systemPrompt = """
                You are a data analyst assistant that helps users query databases using natural language.
                You have access to a database schema and can execute queries to answer user questions.
                
                When users ask questions about data:
                1. Analyze the available schema to understand what data is available
                2. Build appropriate queries using the provided tools
                3. Execute queries and present results in a clear, understandable format
                
                Always explain what query you're executing and why.
                If you cannot answer a question with the available schema, explain what's missing.
                """;
        }
        if (maxQueryResults <= 0) {
            maxQueryResults = 1000;
        }
    }
}
