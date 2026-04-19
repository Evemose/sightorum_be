package com.rorm.viz.dto;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.rorm.viz.dto.chart.ChartBlock;
import org.jspecify.annotations.Nullable;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonClassDescription("""
    One page inside a Digest. Every slot is optional; the frontend only
    renders slots that are present. Typical layout: header on top,
    headline paragraph below, primary chart centered, supporting chart
    on the side, and diagnostics / receipts / caption / callouts in a
    side panel.""")
public record Page(

    @JsonPropertyDescription("Stable identifier for this page within the digest, e.g. 'page-trend'.")
    @JsonProperty(required = true)
    String id,

    @JsonPropertyDescription("Optional page header (title + tag chip).")
    @Nullable PageHeader header,

    @JsonPropertyDescription("Optional short bold paragraph with `**...**` emphasis markers.")
    @Nullable Headline headline,

    @JsonPropertyDescription("Main visual of the page. Omit for text-only pages.")
    @Nullable ChartBlock primaryChart,

    @JsonPropertyDescription("Optional secondary / supporting visual rendered alongside the primary chart.")
    @Nullable ChartBlock punchlineChart,

    @JsonPropertyDescription("""
        Optional diagnostic rule results. Emit in any order; the frontend
        sorts by severity (HIGH -> MED -> LOW).""")
    @Nullable List<FiredCheck> firedChecks,

    @JsonPropertyDescription("Optional provenance / audit chips (e.g. sample size, window, source).")
    @Nullable List<ReceiptItem> receipts,

    @JsonPropertyDescription("Optional small footnote rendered directly under the primary chart.")
    @Nullable ChartCaption caption,

    @JsonPropertyDescription("Optional highlighted advisory boxes (styled by severity).")
    @Nullable List<CalloutBox> callouts
) {}
