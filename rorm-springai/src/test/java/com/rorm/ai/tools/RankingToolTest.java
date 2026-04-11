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
@DisplayName("RankingTool")
@org.junit.jupiter.api.parallel.Execution(org.junit.jupiter.api.parallel.ExecutionMode.SAME_THREAD)
class RankingToolTest {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine")
        .withDatabaseName("testdb").withUsername("test").withPassword("test");

    static RankingTool tool;
    static ToolContext toolContext;
    static ObjectMapper om;
    static org.jooq.DSLContext sharedDsl;

    @BeforeAll
    @SuppressWarnings("SqlNoDataSourceInspection")
    static void bootstrap() throws Exception {
        var id = new BasicAttribute("id", new AttributeLocation("sales", "id"), new DataType.NumericType(19, 0));
        var amount = new BasicAttribute("amount", new AttributeLocation("sales", "amount"), new DataType.NumericType(10, 4));
        var customer = new BasicAttribute("customer", new AttributeLocation("sales", "customer"), new DataType.StringType());
        var region = new BasicAttribute("region", new AttributeLocation("sales", "region"), new DataType.StringType());

        var root = new Root("sales", List.of(id, amount, customer, region),
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
        var formatter = new DescriptiveResponseFormatter(om);
        tool = new RankingTool(executor, formatter);

        var schema = "rank_" + UUID.randomUUID().toString().replace("-", "");
        sharedDsl.execute("CREATE SCHEMA " + schema);
        sharedDsl.execute("SET search_path TO " + schema);
        sharedDsl.execute("""
                CREATE TABLE sales (
                    id        bigserial PRIMARY KEY,
                    amount    numeric(10,4),
                    customer  varchar(50),
                    region    varchar(50)
                )
            """);

        var ctx = new RormToolContext(modelSpace, schema, StepJournal.DEFAULT, null);
        toolContext = new ToolContext(ctx.toMap());
    }

    @BeforeEach
    void truncate() {
        sharedDsl.execute("TRUNCATE TABLE sales RESTART IDENTITY");
    }

    @Test
    @DisplayName("happy path: top 3 by total, no checks fire")
    void happyPathTopThree() throws Exception {
        var sb = new StringBuilder();
        // Customer A: 1000 (way above), B: 500, C: 200, D: 100, E: 50, F: 25
        appendRows(sb, "A", 100, 10.0);
        appendRows(sb, "B", 100, 5.0);
        appendRows(sb, "C", 100, 2.0);
        appendRows(sb, "D", 100, 1.0);
        appendRows(sb, "E", 100, 0.5);
        appendRows(sb, "F", 100, 0.25);
        insertRows(sb.substring(0, sb.length() - 1));

        var json = tool.rankedList(
            "sales", path("customer"), path("amount"), "total", null,
            3, "top", null, null, null, null,
            List.of(), false, toolContext);
        var parsed = parse(json);

        assertThat(parsed.get("success")).isEqualTo(true);
        assertThat((String) parsed.get("headline")).contains("Top 3");
        @SuppressWarnings("unchecked")
        var rawMetrics = (Map<String, Object>) parsed.get("rawMetrics");
        @SuppressWarnings("unchecked")
        var items = (List<Map<String, Object>>) rawMetrics.get("ranked_items");
        assertThat(items).hasSize(3);
        assertThat(items.getFirst().get("key")).isEqualTo("A");
    }

    private static void appendRows(StringBuilder sb, String customer, int rows, double amount) {
        appendRows(sb, customer, rows, amount, "NA");
    }

    @SuppressWarnings({"SqlNoDataSourceInspection", "SameParameterValue"})
    private void insertRows(String valueSql) {
        sharedDsl.execute("INSERT INTO sales (amount, customer, region) VALUES " + valueSql);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(String json) throws Exception {
        return om.readValue(json, Map.class);
    }

    private static void appendRows(StringBuilder sb, String customer, int rows, double amount, String region) {
        for (var i = 0; i < rows; i++) {
            sb.append("(").append(amount).append(", '").append(customer).append("', '").append(region).append("'),");
        }
    }

    @Test
    @DisplayName("R2 gap-to-spread fires when cluster boundary is weak")
    void gapToSpreadFires() throws Exception {
        var sb = new StringBuilder();
        appendRows(sb, "A", 100, 100.0);
        appendRows(sb, "B", 100, 99.0);
        appendRows(sb, "C", 100, 99.0);
        appendRows(sb, "D", 100, 10.0);
        appendRows(sb, "E", 100, 9.0);
        appendRows(sb, "F", 100, 8.0);
        insertRows(sb.substring(0, sb.length() - 1));

        var json = tool.rankedList(
            "sales", path("customer"), path("amount"), "total", null,
            2, "top", null, null, null, null,
            List.of(), false, toolContext);
        var parsed = parse(json);

        @SuppressWarnings("unchecked")
        var fired = (List<Map<String, Object>>) parsed.get("firedChecks");
        var codes = fired.stream().map(f -> (String) f.get("code")).toList();
        assertThat(codes).contains("R2_GAP_TO_SPREAD");
    }

    @Test
    @DisplayName("R4 small-N fires when any ranked item has count below floor")
    void smallNItemFires() throws Exception {
        var sb = new StringBuilder();
        appendRows(sb, "A", 100, 10.0);
        appendRows(sb, "B", 100, 5.0);
        appendRows(sb, "C", 5, 100.0);
        insertRows(sb.substring(0, sb.length() - 1));

        var json = tool.rankedList(
            "sales", path("customer"), path("amount"), "total", null,
            3, "top", null, null, null, null,
            List.of(), false, toolContext);
        var parsed = parse(json);

        @SuppressWarnings("unchecked")
        var fired = (List<Map<String, Object>>) parsed.get("firedChecks");
        var codes = fired.stream().map(f -> (String) f.get("code")).toList();
        assertThat(codes).contains("R4_SMALL_N_ITEM");
    }

    @Test
    @DisplayName("R5 survivorship disclosure fires when flag is true")
    void survivorshipDisclosureFires() throws Exception {
        var sb = new StringBuilder();
        appendRows(sb, "A", 100, 10.0);
        appendRows(sb, "B", 100, 5.0);
        appendRows(sb, "C", 100, 2.0);
        insertRows(sb.substring(0, sb.length() - 1));

        var json = tool.rankedList(
            "sales", path("customer"), path("amount"), "total", null,
            3, "top", null, null, null, null,
            List.of(), true, toolContext);
        var parsed = parse(json);

        @SuppressWarnings("unchecked")
        var fired = (List<Map<String, Object>>) parsed.get("firedChecks");
        var codes = fired.stream().map(f -> (String) f.get("code")).toList();
        assertThat(codes).contains("R5_SURVIVORSHIP");
    }

    @Test
    @DisplayName("invalid direction returns structured error")
    void invalidDirectionReturnsError() throws Exception {
        var sb = new StringBuilder();
        appendRows(sb, "A", 10, 10.0);
        insertRows(sb.substring(0, sb.length() - 1));

        var json = tool.rankedList(
            "sales", path("customer"), path("amount"), "total", null,
            3, "middle", null, null, null, null,
            List.of(), false, toolContext);
        var parsed = parse(json);

        assertThat(parsed.get("success")).isEqualTo(false);
        assertThat((String) parsed.get("error")).contains("direction").contains("top").contains("bottom");
    }

    @Test
    @DisplayName("R1 within-partition churn fires when per-region leaders differ from global top-k")
    void withinPartitionChurnFires() throws Exception {
        var sb = new StringBuilder();
        // Global top by total: A(50000), B(25000), C(10000)
        // Per-region leaders: NA=A, EU=D(still small but region-leader), APAC=E(region-leader)
        appendRows(sb, "A", 100, 500.0, "NA");
        appendRows(sb, "B", 100, 250.0, "NA");
        appendRows(sb, "C", 100, 100.0, "NA");
        appendRows(sb, "D", 100, 50.0, "EU");
        appendRows(sb, "E", 100, 25.0, "APAC");
        appendRows(sb, "F", 100, 10.0, "APAC");
        insertRows(sb.substring(0, sb.length() - 1));

        var json = tool.rankedList(
            "sales", path("customer"), path("amount"), "total", null,
            3, "top", null, null, null, null,
            List.of(path("region")), false, toolContext);
        var parsed = parse(json);

        @SuppressWarnings("unchecked")
        var fired = (List<Map<String, Object>>) parsed.get("firedChecks");
        var codes = fired.stream().map(f -> (String) f.get("code")).toList();
        assertThat(codes).contains("R1_WITHIN_PARTITION_CHURN");
    }
}
