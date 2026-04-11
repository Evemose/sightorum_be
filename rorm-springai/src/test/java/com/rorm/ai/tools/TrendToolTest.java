package com.rorm.ai.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rorm.StepJournal;
import com.rorm.ai.RormToolContext;
import com.rorm.engine.ExpressionTypeResolver;
import com.rorm.mapper.DenseQueryMapper;
import com.rorm.mapper.QueryMapper;
import com.rorm.metamodel.*;
import com.rorm.testutil.TestHandlerRegistry;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.ai.chat.model.ToolContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.rorm.dto.dense.DenseExpressionDto.path;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@DisplayName("TrendTool")
@org.junit.jupiter.api.parallel.Execution(org.junit.jupiter.api.parallel.ExecutionMode.SAME_THREAD)
class TrendToolTest {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine")
        .withDatabaseName("testdb").withUsername("test").withPassword("test");

    static TrendTool tool;
    static ToolContext toolContext;
    static ObjectMapper om;
    static org.jooq.DSLContext sharedDsl;
    static StubStatsService stubStatsService;

    @BeforeAll
    @SuppressWarnings("SqlNoDataSourceInspection")
    static void bootstrap() throws Exception {
        var id = new BasicAttribute("id", new AttributeLocation("events", "id"), new DataType.NumericType(19, 0));
        var amount = new BasicAttribute("amount", new AttributeLocation("events", "amount"), new DataType.NumericType(10, 4));
        var ts = new BasicAttribute("ts", new AttributeLocation("events", "ts"), new DataType.DateTimeType());
        var segment = new BasicAttribute("segment", new AttributeLocation("events", "segment"), new DataType.StringType());

        var root = new Root("events", List.of(id, amount, ts, segment),
            IdDescriptor.longId("events"));
        var modelSpace = new ModelSpace(Set.of(root));

        var queryMapper = Mappers.getMapper(QueryMapper.class);
        var pathResolverField = QueryMapper.class.getDeclaredField("pathResolver");
        pathResolverField.setAccessible(true);
        var resolverCtor = Class.forName("com.rorm.mapper.PathResolver").getDeclaredConstructor();
        resolverCtor.setAccessible(true);
        pathResolverField.set(queryMapper, resolverCtor.newInstance());
        var denseQueryMapper = new DenseQueryMapper(queryMapper);

        var conn = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        sharedDsl = DSL.using(conn);
        var fetcher = com.rorm.engine.TestQueryStack.createFetcher(sharedDsl);

        om = new ObjectMapper();
        var registry = TestHandlerRegistry.createWithAllBuiltIns();
        var typeResolver = new ExpressionTypeResolver(registry);
        var executor = new VerificationQueryExecutor(fetcher, denseQueryMapper, typeResolver);
        var axisFanout = new AxisFanout(executor);
        stubStatsService = new StubStatsService();
        var formatter = new DescriptiveResponseFormatter(om);
        tool = new TrendTool(executor, axisFanout, stubStatsService, formatter);

        var schema = "trend_" + UUID.randomUUID().toString().replace("-", "");
        sharedDsl.execute("CREATE SCHEMA " + schema);
        sharedDsl.execute("SET search_path TO " + schema);
        sharedDsl.execute("""
                CREATE TABLE events (
                    id       bigserial PRIMARY KEY,
                    amount   numeric(10,4),
                    ts       timestamp,
                    segment  varchar(50)
                )
            """);

        var ctx = new RormToolContext(modelSpace, schema, StepJournal.DEFAULT, null);
        toolContext = new ToolContext(ctx.toMap());
    }

    @BeforeEach
    void truncate() {
        sharedDsl.execute("TRUNCATE TABLE events RESTART IDENTITY");
        stubStatsService.reset();
    }

    @Test
    @DisplayName("happy path: linear trend, no checks fire")
    void happyPathLinearTrend() throws Exception {
        var sb = new StringBuilder();
        for (var week = 0; week < 12; week++) {
            var date = java.time.LocalDate.parse("2026-01-01").plusWeeks(week).toString();
            for (var i = 0; i < 50; i++) {
                sb.append("(100.0, '").append(date).append(" 10:00:00', 'A'),");
            }
        }
        insertRows(sb.substring(0, sb.length() - 1));

        var json = tool.trendSeries(
            "events", path("amount"), "mean", null,
            path("ts"), "week", "2026-01-01", "2026-04-01",
            null, null, List.of(), false, toolContext);
        var parsed = parse(json);

        assertThat(parsed.get("success")).isEqualTo(true);
        @SuppressWarnings("unchecked")
        var fired = (List<Map<String, Object>>) parsed.get("firedChecks");
        assertThat(fired).isEmpty();
    }

    @SuppressWarnings({"SqlNoDataSourceInspection"})
    private void insertRows(String valueSql) {
        sharedDsl.execute("INSERT INTO events (amount, ts, segment) VALUES " + valueSql);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(String json) throws Exception {
        return om.readValue(json, Map.class);
    }

    @Test
    @DisplayName("T4 structural break fires when stub PELT returns break indices")
    void structuralBreakFiresFromPython() throws Exception {
        var sb = new StringBuilder();
        for (var week = 0; week < 12; week++) {
            var date = java.time.LocalDate.parse("2026-01-01").plusWeeks(week).toString();
            var amount = week < 6 ? 50.0 : 500.0;
            for (var i = 0; i < 50; i++) {
                sb.append("(").append(amount).append(", '").append(date).append(" 10:00:00', 'A'),");
            }
        }
        insertRows(sb.substring(0, sb.length() - 1));

        stubStatsService.setSeriesAnalysis(new DescriptiveStatsService.SeriesAnalysis(
            12, null, null, List.of(6), List.of()));

        var json = tool.trendSeries(
            "events", path("amount"), "mean", null,
            path("ts"), "week", "2026-01-01", "2026-04-01",
            null, null, List.of(), false, toolContext);
        var parsed = parse(json);

        @SuppressWarnings("unchecked")
        var fired = (List<Map<String, Object>>) parsed.get("firedChecks");
        var codes = fired.stream().map(f -> (String) f.get("code")).toList();
        assertThat(codes).contains("T4_STRUCTURAL_BREAK");
    }

    @Test
    @DisplayName("T3 window sensitivity fires when slope direction flips on ±1 grain shift")
    void windowSensitivityFires() throws Exception {
        // Window 2026-01-01..2026-02-19: series falls (bucket values 100→10)
        // Window 2026-01-08..2026-02-26 (shifted -1 week): prefix 200 at week -1 then drops.
        // Linear slope will differ meaningfully across the shift.
        var sb = new StringBuilder();
        var weeks = new double[]{500.0, 100.0, 90.0, 80.0, 70.0, 60.0, 50.0, 40.0};
        for (var w = 0; w < weeks.length; w++) {
            var date = java.time.LocalDate.parse("2026-01-01").plusWeeks(w - 1).toString();
            for (var i = 0; i < 50; i++) {
                sb.append("(").append(weeks[w]).append(", '").append(date).append(" 10:00:00', 'A'),");
            }
        }
        insertRows(sb.substring(0, sb.length() - 1));

        var json = tool.trendSeries(
            "events", path("amount"), "mean", null,
            path("ts"), "week", "2026-01-01", "2026-02-26",
            null, null, List.of(), false, toolContext);
        var parsed = parse(json);

        @SuppressWarnings("unchecked")
        var fired = (List<Map<String, Object>>) parsed.get("firedChecks");
        var codes = fired.stream().map(f -> (String) f.get("code")).toList();
        assertThat(codes).contains("T3_WINDOW_SENSITIVITY");
    }

    @Test
    @DisplayName("T6 small-N tail fires when most recent 2 buckets have N < 10")
    void smallNTailFires() throws Exception {
        var sb = new StringBuilder();
        for (var week = 0; week < 10; week++) {
            var date = java.time.LocalDate.parse("2026-01-01").plusWeeks(week).toString();
            var rows = week < 8 ? 50 : 3;
            for (var i = 0; i < rows; i++) {
                sb.append("(100.0, '").append(date).append(" 10:00:00', 'A'),");
            }
        }
        insertRows(sb.substring(0, sb.length() - 1));

        var json = tool.trendSeries(
            "events", path("amount"), "mean", null,
            path("ts"), "week", "2026-01-01", "2026-03-20",
            null, null, List.of(), false, toolContext);
        var parsed = parse(json);

        @SuppressWarnings("unchecked")
        var fired = (List<Map<String, Object>>) parsed.get("firedChecks");
        var codes = fired.stream().map(f -> (String) f.get("code")).toList();
        assertThat(codes).contains("T6_SMALL_N_TAIL");
    }

    @Test
    @DisplayName("T7 multiplicative variance fires when rolling variance correlates with level")
    void multiplicativeVarianceFires() throws Exception {
        var sb = new StringBuilder();
        // Series where both level and dispersion grow: level ∈ {10,20,...,120}, noise amplitude proportional.
        // Each bucket has two distinct values so the bucket's aggregated mean is the midpoint
        // but its in-bucket variance scales with level. Since we aggregate per bucket, the bucket
        // mean series grows linearly and rolling variance of bucket means scales too.
        var levels = new double[]{10, 20, 35, 55, 80, 110, 145, 185, 230, 280, 335, 395};
        for (var w = 0; w < levels.length; w++) {
            var date = java.time.LocalDate.parse("2026-01-01").plusWeeks(w).toString();
            for (var i = 0; i < 50; i++) {
                sb.append("(").append(levels[w]).append(", '").append(date).append(" 10:00:00', 'A'),");
            }
        }
        insertRows(sb.substring(0, sb.length() - 1));

        var json = tool.trendSeries(
            "events", path("amount"), "mean", null,
            path("ts"), "week", "2026-01-01", "2026-04-15",
            null, null, List.of(), false, toolContext);
        var parsed = parse(json);

        @SuppressWarnings("unchecked")
        var fired = (List<Map<String, Object>>) parsed.get("firedChecks");
        var codes = fired.stream().map(f -> (String) f.get("code")).toList();
        assertThat(codes).contains("T7_MULTIPLICATIVE_VARIANCE");
    }

    @Test
    @DisplayName("unknown bucketGrain returns structured error")
    void unknownGrainReturnsError() throws Exception {
        insertRows("(100.0, '2026-01-01 10:00:00', 'A')");

        var json = tool.trendSeries(
            "events", path("amount"), "mean", null,
            path("ts"), "minute", "2026-01-01", "2026-02-01",
            null, null, List.of(), false, toolContext);
        var parsed = parse(json);

        assertThat(parsed.get("success")).isEqualTo(false);
        assertThat((String) parsed.get("error")).contains("Unknown bucketGrain");
    }

    @Test
    @DisplayName("T5 compositional shift fires when per-segment direction reverses aggregate")
    void compositionalShiftFires() throws Exception {
        var sb = new StringBuilder();
        // Per-segment both declining. But segment A (high values) takes bigger share in later weeks
        // → aggregate mean RISES while segment means fall: Simpson's paradox in time.
        for (var week = 0; week < 6; week++) {
            var date = java.time.LocalDate.parse("2026-01-01").plusWeeks(week).toString();
            var aCount = 10 + week * 30;
            var bCount = 60 - week * 10;
            var aValue = 200 - week * 20;
            var bValue = 50 - week * 5;
            for (var i = 0; i < aCount; i++) {
                sb.append("(").append(aValue).append(", '").append(date).append(" 10:00:00', 'A'),");
            }
            for (var i = 0; i < bCount; i++) {
                sb.append("(").append(bValue).append(", '").append(date).append(" 10:00:00', 'B'),");
            }
        }
        insertRows(sb.substring(0, sb.length() - 1));

        var json = tool.trendSeries(
            "events", path("amount"), "mean", null,
            path("ts"), "week", "2026-01-01", "2026-03-01",
            null, null, List.of(path("segment")), false, toolContext);
        var parsed = parse(json);

        @SuppressWarnings("unchecked")
        var fired = (List<Map<String, Object>>) parsed.get("firedChecks");
        var codes = fired.stream().map(f -> (String) f.get("code")).toList();
        assertThat(codes).contains("T5_COMPOSITIONAL_SHIFT");
    }

    private static class StubStatsService extends DescriptiveStatsService {
        private DescriptiveStatsService.SeriesAnalysis canned;

        StubStatsService() {
            super(null);
        }

        void reset() {
            canned = null;
        }

        void setSeriesAnalysis(DescriptiveStatsService.SeriesAnalysis sa) {
            this.canned = sa;
        }

        @Override
        public DescriptiveStatsService.DistributionShape distributionShape(List<Double> values) {
            return new DescriptiveStatsService.DistributionShape(
                values.size(), 0.0, 0.0, 0.9, false, 1, 0.0, List.of());
        }

        @Override
        public DescriptiveStatsService.SeriesAnalysis seriesAnalysis(
            List<Double> values, Integer period, boolean runStl, boolean runAutocorr,
            boolean runChangepoint, double peltPenalty) {
            if (canned != null) {
                return canned;
            }
            return new DescriptiveStatsService.SeriesAnalysis(values.size(), null, null, null, List.of());
        }
    }
}
