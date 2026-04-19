package com.rorm.client.chat.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = ChatNode.Text.class, name = "text"),
    @JsonSubTypes.Type(value = ChatNode.Quote.class, name = "quote"),
    @JsonSubTypes.Type(value = ChatNode.Headline.class, name = "headline"),
    @JsonSubTypes.Type(value = ChatNode.Chart.class, name = "chart"),
    @JsonSubTypes.Type(value = ChatNode.KpiCard.class, name = "kpi_card"),
    @JsonSubTypes.Type(value = ChatNode.Table.class, name = "table"),
    @JsonSubTypes.Type(value = ChatNode.Callout.class, name = "callout"),
    @JsonSubTypes.Type(value = ChatNode.AnalysisView.class, name = "analysis_view"),
    @JsonSubTypes.Type(value = ChatNode.Import.class, name = "import"),
    @JsonSubTypes.Type(value = ChatNode.Ref.class, name = "ref")
})
public sealed interface ChatNode {

    record Text(String content) implements ChatNode {}

    record Quote(String content, @Nullable String source) implements ChatNode {}

    record Headline(int level, String text) implements ChatNode {}

    record Chart(
        String chartType,
        String title,
        @Nullable String description,
        @Nullable List<Map<String, Object>> data
    ) implements ChatNode {}

    record KpiCard(
        String label,
        String value,
        @Nullable String delta,
        @Nullable String trend
    ) implements ChatNode {}

    record Table(
        @Nullable String title,
        List<String> columns,
        List<List<Object>> rows
    ) implements ChatNode {}

    record Callout(
        String severity,
        String message,
        @Nullable String detail
    ) implements ChatNode {}

    record AnalysisView(
        String title,
        String status,
        @Nullable String runId
    ) implements ChatNode {}

    record Import(String jobId, String targetSchema) implements ChatNode {}

    record Ref(String refType, String runId) implements ChatNode {}
}
