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
@DisplayName("SummaryStatTool")
@org.junit.jupiter.api.parallel.Execution(org.junit.jupiter.api.parallel.ExecutionMode.SAME_THREAD)
class SummaryStatToolTest {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine")
        .withDatabaseName("testdb").withUsername("test").withPassword("test");

    static SummaryStatTool tool;
    static ToolContext toolContext;
    static ObjectMapper om;
    static org.jooq.DSLContext sharedDsl;
    static StubStatsService stubStatsService;

    @BeforeAll
    @SuppressWarnings("SqlNoDataSourceInspection")
    static void bootstrap() throws Exception {
        var id = new BasicAttribute("id", new AttributeLocation("sales", "id"), new DataType.NumericType(19, 0));
        var amount = new BasicAttribute("amount", new AttributeLocation("sales", "amount"), new DataType.NumericType(10, 4));
        var qty = new BasicAttribute("qty", new AttributeLocation("sales", "qty"), new DataType.NumericType(10, 4));
        var region = new BasicAttribute("region", new AttributeLocation("sales", "region"), new DataType.StringType());
        var channel = new BasicAttribute("channel", new AttributeLocation("sales", "channel"), new DataType.StringType());

        var root = new Root("sales", List.of(id, amount, qty, region, channel),
            IdDescriptor.longId("sales"));
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
        tool = new SummaryStatTool(executor, axisFanout, stubStatsService, formatter);

        var schema = "det_" + UUID.randomUUID().toString().replace("-", "");
        sharedDsl.execute("CREATE SCHEMA " + schema);
        sharedDsl.execute("SET search_path TO " + schema);
        sharedDsl.execute("""
                CREATE TABLE sales (
                    id       bigserial PRIMARY KEY,
                    amount   numeric(10,4),
                    qty      numeric(10,4),
                    region   varchar(50),
                    channel  varchar(50)
                )
            """);

        var ctx = new RormToolContext(modelSpace, schema, StepJournal.DEFAULT, null);
        toolContext = new ToolContext(ctx.toMap());
    }

    @BeforeEach
    void truncate() {
        sharedDsl.execute("TRUNCATE TABLE sales RESTART IDENTITY");
        stubStatsService.reset();
    }

    @Test
    @DisplayName("happy path: uniform data, mean kind, no axes, no fires")
    void happyPathMeanNoAxesNoFires() throws Exception {
        var sb = new StringBuilder();
        for (var i = 0; i < 100; i++) {
            sb.append("(100.0, 1.0, 'NA', 'online'),");
        }
        insertRows(sb.substring(0, sb.length() - 1));

        var json = tool.summaryStatistic(
            "sales", path("amount"), "mean", null, null, null, null, null,
            List.of(), false, toolContext);
        var parsed = parse(json);

        assertThat(parsed.get("success")).isEqualTo(true);
        assertThat(parsed.get("archetype")).isEqualTo("SUMMARY_STAT");
        assertThat((String) parsed.get("headline")).contains("100");
        @SuppressWarnings("unchecked")
        var fired = (List<Map<String, Object>>) parsed.get("firedChecks");
        assertThat(fired).isEmpty();
    }

    @SuppressWarnings({"SqlNoDataSourceInspection", "SameParameterValue"})
    private void insertRows(String valueSql) {
        sharedDsl.execute("INSERT INTO sales (amount, qty, region, channel) VALUES " + valueSql);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(String json) throws Exception {
        return om.readValue(json, Map.class);
    }

    @Test
    @DisplayName("median kind uses PERCENTILE_CONT and reports median")
    void medianKindUsesPercentileCont() throws Exception {
        var sb = new StringBuilder();
        for (var i = 1; i <= 100; i++) {
            sb.append("(").append(i).append(", 1.0, 'NA', 'online'),");
        }
        insertRows(sb.substring(0, sb.length() - 1));

        var json = tool.summaryStatistic(
            "sales", path("amount"), "median", null, null, null, null, null,
            List.of(), false, toolContext);
        var parsed = parse(json);

        assertThat(parsed.get("success")).isEqualTo(true);
        @SuppressWarnings("unchecked")
        var rawMetrics = (Map<String, Object>) parsed.get("rawMetrics");
        var value = ((Number) rawMetrics.get("value")).doubleValue();
        assertThat(value).isBetween(50.0, 51.0);
    }

    @Test
    @DisplayName("C1 heterogeneity fires when segments diverge by >3x")
    void heterogeneityFires() throws Exception {
        var sb = new StringBuilder();
        for (var i = 0; i < 100; i++) {
            sb.append("(10.0, 1.0, 'NA', 'online'),");
        }
        for (var i = 0; i < 100; i++) {
            sb.append("(100.0, 1.0, 'EU', 'online'),");
        }
        insertRows(sb.substring(0, sb.length() - 1));

        var json = tool.summaryStatistic(
            "sales", path("amount"), "mean", null, null, null, null, null,
            List.of(path("region")), false, toolContext);
        var parsed = parse(json);

        @SuppressWarnings("unchecked")
        var fired = (List<Map<String, Object>>) parsed.get("firedChecks");
        assertThat(fired).isNotEmpty();
        assertThat(fired.getFirst().get("code")).isEqualTo("C1_HETEROGENEITY");
        assertThat((String) fired.getFirst().get("severity")).isEqualTo("HIGH");
    }

    @Test
    @DisplayName("C3 outlier sensitivity fires when 1% extreme values dominate the mean")
    void outlierSensitivityFires() throws Exception {
        var sb = new StringBuilder();
        for (var i = 0; i < 999; i++) {
            sb.append("(10.0, 1.0, 'NA', 'online'),");
        }
        sb.append("(900000.0, 1.0, 'NA', 'online')");
        insertRows(sb.toString());

        var json = tool.summaryStatistic(
            "sales", path("amount"), "mean", null, null, null, null, null,
            List.of(), false, toolContext);
        var parsed = parse(json);

        @SuppressWarnings("unchecked")
        var fired = (List<Map<String, Object>>) parsed.get("firedChecks");
        var codes = fired.stream().map(f -> (String) f.get("code")).toList();
        assertThat(codes).contains("C3_OUTLIER_SENSITIVE");
    }

    @Test
    @DisplayName("multi-fire: severity ordering pins heterogeneity above shape and survivorship")
    void multiFireSeverityOrdering() throws Exception {
        var sb = new StringBuilder();
        for (var i = 0; i < 50; i++) {
            sb.append("(10.0, 1.0, 'NA', 'online'),");
        }
        for (var i = 0; i < 50; i++) {
            sb.append("(500.0, 1.0, 'EU', 'online'),");
        }
        insertRows(sb.substring(0, sb.length() - 1));

        stubStatsService.setDistributionShape(new DescriptiveStatsService.DistributionShape(
            100, 3.0, 5.0, 0.01, true, 2, 1.6, List.of()
        ));

        var json = tool.summaryStatistic(
            "sales", path("amount"), "mean", null, null, null, null, null,
            List.of(path("region")), true, toolContext);
        var parsed = parse(json);

        @SuppressWarnings("unchecked")
        var fired = (List<Map<String, Object>>) parsed.get("firedChecks");
        assertThat(fired).hasSizeLessThanOrEqualTo(3);
        assertThat(fired.getFirst().get("code")).isEqualTo("C1_HETEROGENEITY");
        var codes = fired.stream().map(f -> (String) f.get("code")).toList();
        assertThat(codes).contains("C2_DISTRIBUTIONAL_SHAPE");
    }

    @Test
    @DisplayName("C4 small-N fires when population is below floor")
    void smallNPopulationFires() throws Exception {
        var sb = new StringBuilder();
        for (var i = 0; i < 10; i++) {
            sb.append("(100.0, 1.0, 'NA', 'online'),");
        }
        insertRows(sb.substring(0, sb.length() - 1));

        var json = tool.summaryStatistic(
            "sales", path("amount"), "mean", null, null, null, null, null,
            List.of(), false, toolContext);
        var parsed = parse(json);

        @SuppressWarnings("unchecked")
        var fired = (List<Map<String, Object>>) parsed.get("firedChecks");
        var codes = fired.stream().map(f -> (String) f.get("code")).toList();
        assertThat(codes).contains("C4_SMALL_N");
    }

    @Test
    @DisplayName("C4 small-N fires when a surfaced segment is below floor even if population is adequate")
    void smallNSegmentFires() throws Exception {
        var sb = new StringBuilder();
        for (var i = 0; i < 100; i++) {
            sb.append("(100.0, 1.0, 'NA', 'online'),");
        }
        sb.append("(100.0, 1.0, 'EU', 'online'),");
        sb.append("(100.0, 1.0, 'EU', 'online')");
        insertRows(sb.toString());

        var json = tool.summaryStatistic(
            "sales", path("amount"), "mean", null, null, null, null, null,
            List.of(path("region")), false, toolContext);
        var parsed = parse(json);

        @SuppressWarnings("unchecked")
        var fired = (List<Map<String, Object>>) parsed.get("firedChecks");
        var codes = fired.stream().map(f -> (String) f.get("code")).toList();
        assertThat(codes).contains("C4_SMALL_N");
    }

    @Test
    @DisplayName("C5 denominator stability fires when ratio denominator varies widely across segments")
    void denominatorStabilityFires() throws Exception {
        var sb = new StringBuilder();
        for (var i = 0; i < 50; i++) {
            sb.append("(10.0, 5.0, 'NA', 'online'),");
        }
        for (var i = 0; i < 50; i++) {
            sb.append("(10.0, 0.1, 'EU', 'online'),");
        }
        insertRows(sb.substring(0, sb.length() - 1));

        var json = tool.summaryStatistic(
            "sales", path("amount"), "ratio", path("qty"), null,
            null, null, null, List.of(path("region")), false, toolContext);
        var parsed = parse(json);

        @SuppressWarnings("unchecked")
        var fired = (List<Map<String, Object>>) parsed.get("firedChecks");
        var codes = fired.stream().map(f -> (String) f.get("code")).toList();
        assertThat(codes).contains("C5_DENOMINATOR_UNSTABLE");
    }

    @Test
    @DisplayName("unknown measure kind returns structured error")
    void unknownKindReturnsError() throws Exception {
        var sb = new StringBuilder();
        for (var i = 0; i < 10; i++) {
            sb.append("(100.0, 1.0, 'NA', 'online'),");
        }
        insertRows(sb.substring(0, sb.length() - 1));

        var json = tool.summaryStatistic(
            "sales", path("amount"), "percentile_50", null, null, null, null, null,
            List.of(), false, toolContext);
        var parsed = parse(json);

        assertThat(parsed.get("success")).isEqualTo(false);
        assertThat((String) parsed.get("error")).contains("Unknown kind").contains("percentile_50");
    }

    @Test
    @DisplayName("survivorship disclosure fires when flag is true")
    void survivorshipDisclosureFires() throws Exception {
        var sb = new StringBuilder();
        for (var i = 0; i < 100; i++) {
            sb.append("(100.0, 1.0, 'NA', 'online'),");
        }
        insertRows(sb.substring(0, sb.length() - 1));

        var json = tool.summaryStatistic(
            "sales", path("amount"), "mean", null, null, null, null, null,
            List.of(), true, toolContext);
        var parsed = parse(json);

        @SuppressWarnings("unchecked")
        var fired = (List<Map<String, Object>>) parsed.get("firedChecks");
        var codes = fired.stream().map(f -> (String) f.get("code")).toList();
        assertThat(codes).contains("C6_SURVIVORSHIP");
    }

    private static class StubStatsService extends DescriptiveStatsService {
        private DescriptiveStatsService.DistributionShape canned;

        StubStatsService() {
            super(null);
        }

        void reset() {
            canned = null;
        }

        void setDistributionShape(DescriptiveStatsService.DistributionShape shape) {
            this.canned = shape;
        }

        @Override
        public DescriptiveStatsService.DistributionShape distributionShape(List<Double> values) {
            if (canned != null) {
                return canned;
            }
            return new DescriptiveStatsService.DistributionShape(
                values.size(), 0.0, 0.0, 0.9, false, 1, 0.0, List.of());
        }

        @Override
        public DescriptiveStatsService.SeriesAnalysis seriesAnalysis(
            List<Double> values, Integer period, boolean runStl, boolean runAutocorr,
            boolean runChangepoint, double peltPenalty) {
            return new DescriptiveStatsService.SeriesAnalysis(values.size(), null, null, null, List.of());
        }
    }
}
