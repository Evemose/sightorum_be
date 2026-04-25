package com.rorm.client.chat.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.rorm.viz.dto.*;
import com.rorm.viz.dto.chart.ChartBlock;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Streaming unit of the chat copilot's typed output. Most variants wrap an
 * existing {@code com.rorm.viz.dto} type so the same renderer that handles a
 * {@link com.rorm.viz.dto.Digest} can render inline chat content.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = ChatNode.TextNode.class, name = "text"),
    @JsonSubTypes.Type(value = ChatNode.HeadlineNode.class, name = "headline"),
    @JsonSubTypes.Type(value = ChatNode.CalloutNode.class, name = "callout"),
    @JsonSubTypes.Type(value = ChatNode.ChartNode.class, name = "chart"),
    @JsonSubTypes.Type(value = ChatNode.CheckNode.class, name = "fired_check"),
    @JsonSubTypes.Type(value = ChatNode.CaptionNode.class, name = "caption"),
    @JsonSubTypes.Type(value = ChatNode.ReceiptsNode.class, name = "receipts"),
    @JsonSubTypes.Type(value = ChatNode.TableNode.class, name = "table"),
    @JsonSubTypes.Type(value = ChatNode.AnalysisRefNode.class, name = "analysis_ref"),
    @JsonSubTypes.Type(value = ChatNode.ImportRefNode.class, name = "import_ref")
})
public sealed interface ChatNode {

    /**
     * Narrative prose. Markdown — inline emphasis, lists, code spans, links.
     */
    record TextNode(
        @Schema(maxLength = 20000) String markdown
    ) implements ChatNode {}

    /**
     * Section-introducing bold paragraph with **…** emphasis on numbers/entities.
     */
    record HeadlineNode(Headline headline) implements ChatNode {}

    /**
     * Advisory with HIGH/MED/LOW severity.
     */
    record CalloutNode(CalloutBox callout) implements ChatNode {}

    /**
     * Any renderable chart from the viz catalog (line, bar, kpi-card, sankey, …).
     */
    record ChartNode(ChartBlock chart) implements ChatNode {}

    /**
     * Diagnostic rule result (stable code + severity + text).
     */
    record CheckNode(FiredCheck check) implements ChatNode {}

    /**
     * Small footnote under a preceding chart.
     */
    record CaptionNode(ChartCaption caption) implements ChatNode {}

    /**
     * Row of provenance chips — sample size, window, source, method.
     */
    record ReceiptsNode(List<ReceiptItem> items) implements ChatNode {}

    /**
     * Plain tabular data. For bar-enhanced tables, emit a ChartNode(table-lens) instead.
     */
    record TableNode(
        @Schema(maxLength = 200) @Nullable String title,
        List<String> columns,
        List<List<Object>> rows
    ) implements ChatNode {}

    /** Reference to a running/completed analysis run. FE subscribes to its event stream. */
    record AnalysisRefNode(
        @Schema(maxLength = 100) String runId
    ) implements ChatNode {}

    /** Reference to an import job. FE subscribes to its progress stream. */
    record ImportRefNode(
        @Schema(maxLength = 100) String jobId
    ) implements ChatNode {}
}
