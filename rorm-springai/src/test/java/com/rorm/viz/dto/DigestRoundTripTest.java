package com.rorm.viz.dto;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.rorm.viz.dto.chart.ChartBlock;
import com.rorm.viz.dto.chart.LineChartBlocks;
import com.rorm.viz.dto.data.GroupedTimeSeriesData;
import com.rorm.viz.dto.data.TimeCoord;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the polymorphic / union-shape DTOs against deserialization
 * regressions. The agent pipeline deserializes LLM JSON into a Digest;
 * any sealed interface or union shape that loses its deserializer will
 * fail production the next time the agent runs. This test keeps the
 * whole shape live at build time.
 */
class DigestRoundTripTest {

    private final ObjectMapper mapper = JsonMapper.builder()
        .addModule(new JavaTimeModule())
        .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
        .build();

    @Test
    void roundTripsWithDataCoordAnchorAndIsoTimeCoord() throws Exception {
        var digest = digestWith(
            new AnnotationAnchor.DataCoord(AxisCoord.of(3.5), AxisCoord.of(12.0)),
            new GroupedTimeSeriesData.Point(TimeCoord.ofIso("2026-02-16"), 100.0));

        var json = mapper.writeValueAsString(digest);
        var back = mapper.readValue(json, Digest.class);

        assertThat(back).isEqualTo(digest);
    }

    private Digest digestWith(AnnotationAnchor anchor, GroupedTimeSeriesData.Point point) {
        var annotation = new AnnotationDef("peak", anchor);
        var scaffold = new SharedChartProps(null, null, null, List.of(annotation), null, null, null);
        var series = new GroupedTimeSeriesData.Series("revenue", "base", List.of(point));
        ChartBlock chart = new LineChartBlocks.LineChartBlock(
            scaffold, new GroupedTimeSeriesData(List.of(series)), null, true);
        var page = new Page("p1", null, null, chart, null, null, null, null, null);
        return new Digest("digest-x", null, null, List.of(page),
            Instant.parse("2026-04-16T08:00:00Z"));
    }

    @Test
    void roundTripsWithCornerAnchorAndEpochMsTimeCoord() throws Exception {
        var digest = digestWith(
            AnnotationAnchor.Corner.TOP_RIGHT,
            new GroupedTimeSeriesData.Point(TimeCoord.ofEpochMs(1_739_664_000_000L), 200.0));

        var json = mapper.writeValueAsString(digest);
        var back = mapper.readValue(json, Digest.class);

        assertThat(back).isEqualTo(digest);
    }

    @Test
    void roundTripsWithStringAxisCoord() throws Exception {
        var digest = digestWith(
            new AnnotationAnchor.DataCoord(AxisCoord.of("2017-07-01"), AxisCoord.of(0.28)),
            new GroupedTimeSeriesData.Point(TimeCoord.ofIso("2026-02-16"), 100.0));

        var json = mapper.writeValueAsString(digest);
        var back = mapper.readValue(json, Digest.class);

        assertThat(back).isEqualTo(digest);
    }

    @Test
    void deserializesRawLlmStyleJson() throws Exception {
        var raw = """
            {
              "id": "digest-x",
              "pages": [{
                "id": "p1",
                "primaryChart": {
                  "kind": "line-chart",
                  "scaffold": {
                    "annotations": [
                      {"text": "peak", "anchor": {"x": 5, "y": 12}},
                      {"text": "legend", "anchor": "top-left"},
                      {"text": "spike", "anchor": {"x": "2017-07-01", "y": 0.28}},
                      {"text": "bucket", "anchor": {"x": "cat-A", "y": 42}}
                    ]
                  },
                  "data": {
                    "series": [{
                      "name": "revenue",
                      "color": "BASE",
                      "points": [
                        {"t": "2026-02-16", "value": 100.0},
                        {"t": 1739664000000, "value": 120.0}
                      ]
                    }]
                  }
                }
              }],
              "generatedAt": "2026-04-16T08:00:00Z"
            }
            """;

        var digest = mapper.readValue(raw, Digest.class);

        var primary = (LineChartBlocks.LineChartBlock) digest.pages().getFirst().primaryChart();
        var anchors = primary.scaffold().annotations();

        var numericCoord = (AnnotationAnchor.DataCoord) anchors.get(0).anchor();
        assertThat(numericCoord.x().raw()).isInstanceOfAny(Integer.class, Long.class);
        assertThat(numericCoord.y().raw()).isInstanceOfAny(Integer.class, Long.class);

        assertThat(anchors.get(1).anchor()).isEqualTo(AnnotationAnchor.Corner.TOP_LEFT);

        var timeCoord = (AnnotationAnchor.DataCoord) anchors.get(2).anchor();
        assertThat(timeCoord.x().raw()).isEqualTo("2017-07-01");
        assertThat(timeCoord.y().raw()).isInstanceOfAny(Double.class, Float.class);

        var categoricalCoord = (AnnotationAnchor.DataCoord) anchors.get(3).anchor();
        assertThat(categoricalCoord.x().raw()).isEqualTo("cat-A");

        var series = primary.data().series().getFirst();
        assertThat(series.color()).isEqualToIgnoringCase("base");

        var points = series.points();
        assertThat(points.get(0).t().raw()).isEqualTo("2026-02-16");
        assertThat(points.get(1).t().raw()).isInstanceOfAny(Long.class, Integer.class);
    }
}
