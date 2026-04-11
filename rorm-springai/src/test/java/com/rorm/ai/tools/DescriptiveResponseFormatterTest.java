package com.rorm.ai.tools;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rorm.ai.tools.DescriptiveDigest.Archetype;
import com.rorm.ai.tools.DescriptiveDigest.FiredCheck;
import com.rorm.ai.tools.DescriptiveDigest.Receipts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DescriptiveResponseFormatter")
class DescriptiveResponseFormatterTest {

    private static final Receipts SIMPLE_RECEIPTS = new Receipts(
        null, "orders", List.of("axis_0"), Archetype.SUMMARY_STAT, List.of());
    private final ObjectMapper om = new ObjectMapper();
    private final DescriptiveResponseFormatter formatter = new DescriptiveResponseFormatter(om);

    private Map<String, Object> parse(String json) throws Exception {
        return om.readValue(json, new TypeReference<>() {});
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> firedChecks(Map<String, Object> parsed) {
        return (List<Map<String, Object>>) parsed.get("firedChecks");
    }

    @Nested
    @DisplayName("severity ordering and pinning")
    class SeverityOrdering {

        @Test
        @DisplayName("K1 frame mismatch is pinned at the top regardless of severity")
        void k1Pinned() throws Exception {
            var k1 = FiredCheck.high("K1_FRAME_MISMATCH", "roots differ",
                Map.of("mismatches", List.of("root")));
            var c1 = FiredCheck.high("C1_HETEROGENEITY", "max/min > 3",
                Map.of("top_axes", List.of()));
            var c3 = FiredCheck.med("C3_OUTLIER_SENSITIVE", "trimmed shifts by 20%",
                Map.of("shift_ratio", 0.2));

            var json = formatter.format(Archetype.COMPARISON, "headline",
                List.of(c1, c3, k1), SIMPLE_RECEIPTS, Map.of());

            var fired = firedChecks(parse(json));
            assertThat(fired)
                .hasSize(3)
                .extracting(m -> (String) m.get("code"))
                .containsExactly("K1_FRAME_MISMATCH", "C1_HETEROGENEITY", "C3_OUTLIER_SENSITIVE");
        }

        @Test
        @DisplayName("K3 Simpson reversal is pinned at the top")
        void k3Pinned() throws Exception {
            var c1 = FiredCheck.high("C1_HETEROGENEITY", "het", Map.of());
            var k3 = FiredCheck.high("K3_SIMPSON_REVERSAL", "simpson", Map.of());
            var k4 = FiredCheck.med("K4_MAGNITUDE_MISMATCH", "mag", Map.of());

            var json = formatter.format(Archetype.COMPARISON, "headline",
                List.of(c1, k4, k3), SIMPLE_RECEIPTS, Map.of());

            var fired = firedChecks(parse(json));
            assertThat(fired)
                .extracting(m -> (String) m.get("code"))
                .containsExactly("K3_SIMPSON_REVERSAL", "C1_HETEROGENEITY", "K4_MAGNITUDE_MISMATCH");
        }

        @Test
        @DisplayName("heterogeneity ranks above structural break which ranks above shape")
        void categoryOrdering() throws Exception {
            var shape = FiredCheck.med("C2_DISTRIBUTIONAL_SHAPE", "skew", Map.of());
            var breakCheck = FiredCheck.high("T4_STRUCTURAL_BREAK", "break", Map.of());
            var het = FiredCheck.high("C1_HETEROGENEITY", "het", Map.of());

            var json = formatter.format(Archetype.TREND, "headline",
                List.of(shape, breakCheck, het), SIMPLE_RECEIPTS, Map.of());

            var fired = firedChecks(parse(json));
            assertThat(fired)
                .extracting(m -> (String) m.get("code"))
                .containsExactly("C1_HETEROGENEITY", "T4_STRUCTURAL_BREAK", "C2_DISTRIBUTIONAL_SHAPE");
        }

        @Test
        @DisplayName("within the same priority category, higher severity comes first")
        void severityBreaksTies() throws Exception {
            var low = FiredCheck.low("C6_SURVIVORSHIP", "survivors", Map.of());
            var med = FiredCheck.med("T7_MULTIPLICATIVE_VARIANCE", "variance", Map.of());
            var high = FiredCheck.high("T4_STRUCTURAL_BREAK", "break", Map.of());

            var json = formatter.format(Archetype.TREND, "headline",
                List.of(low, med, high), SIMPLE_RECEIPTS, Map.of());

            var fired = firedChecks(parse(json));
            assertThat(fired)
                .extracting(m -> (String) m.get("code"))
                .containsExactly("T4_STRUCTURAL_BREAK", "T7_MULTIPLICATIVE_VARIANCE", "C6_SURVIVORSHIP");
        }
    }

    @Nested
    @DisplayName("cap behavior")
    class Capping {

        @Test
        @DisplayName("caps to top 3 when 5 checks fire")
        void capsToThreeWhenFiveFire() throws Exception {
            var fires = List.of(
                FiredCheck.low("C6_SURVIVORSHIP", "a", Map.of()),
                FiredCheck.low("C4_SMALL_N", "b", Map.of()),
                FiredCheck.med("C3_OUTLIER_SENSITIVE", "c", Map.of()),
                FiredCheck.med("C2_DISTRIBUTIONAL_SHAPE", "d", Map.of()),
                FiredCheck.high("C1_HETEROGENEITY", "e", Map.of()));

            var json = formatter.format(Archetype.SUMMARY_STAT, "h", fires, SIMPLE_RECEIPTS, Map.of());

            var fired = firedChecks(parse(json));
            assertThat(fired)
                .hasSize(3)
                .extracting(m -> (String) m.get("code"))
                .containsExactly("C1_HETEROGENEITY", "C3_OUTLIER_SENSITIVE", "C2_DISTRIBUTIONAL_SHAPE");
        }

        @Test
        @DisplayName("does not cap when exactly 3 checks fire")
        void doesNotCapAtThree() throws Exception {
            var fires = List.of(
                FiredCheck.high("C1_HETEROGENEITY", "a", Map.of()),
                FiredCheck.med("C3_OUTLIER_SENSITIVE", "b", Map.of()),
                FiredCheck.low("C6_SURVIVORSHIP", "c", Map.of()));

            var json = formatter.format(Archetype.SUMMARY_STAT, "h", fires, SIMPLE_RECEIPTS, Map.of());

            assertThat(firedChecks(parse(json))).hasSize(3);
        }

        @Test
        @DisplayName("does not cap when only 1 check fires")
        void doesNotCapAtOne() throws Exception {
            var fires = List.of(FiredCheck.high("C1_HETEROGENEITY", "a", Map.of()));

            var json = formatter.format(Archetype.SUMMARY_STAT, "h", fires, SIMPLE_RECEIPTS, Map.of());

            assertThat(firedChecks(parse(json))).hasSize(1);
        }

        @Test
        @DisplayName("empty fired list produces empty list in digest")
        void emptyFiredList() throws Exception {
            var json = formatter.format(Archetype.SUMMARY_STAT, "h", List.of(), SIMPLE_RECEIPTS, Map.of());

            assertThat(firedChecks(parse(json))).isEmpty();
        }
    }

    @Nested
    @DisplayName("JSON shape")
    class JsonShape {

        @Test
        @DisplayName("ok response carries archetype, headline, receipts, and rawMetrics")
        void okResponseShape() throws Exception {
            var receipts = new Receipts("2024-01..2024-12", "orders (filtered)",
                List.of("axis_0", "axis_1"), Archetype.SUMMARY_STAT, List.of("C2_DISTRIBUTIONAL_SHAPE"));
            var rawMetrics = Map.<String, Object>of("value", 42.0, "n", 1000L);

            var json = formatter.format(Archetype.SUMMARY_STAT, "mean(x) = 42.0 (N=1000)",
                List.of(), receipts, rawMetrics);
            var parsed = parse(json);

            assertThat(parsed)
                .containsEntry("success", true)
                .containsEntry("archetype", "SUMMARY_STAT")
                .containsEntry("headline", "mean(x) = 42.0 (N=1000)");

            @SuppressWarnings("unchecked")
            var receiptsMap = (Map<String, Object>) parsed.get("receipts");
            assertThat(receiptsMap)
                .containsEntry("window", "2024-01..2024-12")
                .containsEntry("population", "orders (filtered)")
                .containsEntry("archetype", "SUMMARY_STAT");
            @SuppressWarnings("unchecked")
            var axesConsidered = (List<Object>) receiptsMap.get("axesConsidered");
            assertThat(axesConsidered).containsExactly("axis_0", "axis_1");
            @SuppressWarnings("unchecked")
            var unavailable = (List<Object>) receiptsMap.get("unavailableChecks");
            assertThat(unavailable).containsExactly("C2_DISTRIBUTIONAL_SHAPE");

            @SuppressWarnings("unchecked")
            var metrics = (Map<String, Object>) parsed.get("rawMetrics");
            assertThat(metrics).containsEntry("value", 42.0);
        }

        @Test
        @DisplayName("fired check serializes with code, severity, message, and payload")
        void firedCheckShape() throws Exception {
            var check = FiredCheck.high("C1_HETEROGENEITY",
                "Segments diverge >3x",
                Map.of("top_axes", List.of("region", "channel")));

            var json = formatter.format(Archetype.SUMMARY_STAT, "h", List.of(check),
                SIMPLE_RECEIPTS, Map.of());
            var parsed = parse(json);

            var fired = firedChecks(parsed);
            assertThat(fired).hasSize(1);
            assertThat(fired.getFirst())
                .containsEntry("code", "C1_HETEROGENEITY")
                .containsEntry("severity", "HIGH")
                .containsEntry("message", "Segments diverge >3x");
            @SuppressWarnings("unchecked")
            var payload = (Map<String, Object>) fired.getFirst().get("payload");
            @SuppressWarnings("unchecked")
            var topAxes = (List<Object>) payload.get("top_axes");
            assertThat(topAxes).containsExactly("region", "channel");
        }

        @Test
        @DisplayName("error response has success=false and error message, no headline")
        void errorResponseShape() throws Exception {
            var json = formatter.error(Archetype.COMPARISON, "Frame mismatch: root differs");

            var parsed = parse(json);
            assertThat(parsed)
                .containsEntry("success", false)
                .containsEntry("archetype", "COMPARISON")
                .containsEntry("error", "Frame mismatch: root differs")
                .doesNotContainKey("headline");
        }
    }
}
