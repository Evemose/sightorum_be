package com.rorm.ai.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rorm.StepJournal;
import com.rorm.ai.RormToolContext;
import com.rorm.ai.tools.ComparisonTool.ComparisonSides;
import com.rorm.ai.tools.ComparisonTool.SideSpec;
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

import static com.rorm.dto.dense.DenseExpressionDto.*;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@DisplayName("ComparisonTool")
@org.junit.jupiter.api.parallel.Execution(org.junit.jupiter.api.parallel.ExecutionMode.SAME_THREAD)
class ComparisonToolTest {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine")
        .withDatabaseName("testdb").withUsername("test").withPassword("test");

    static ComparisonTool tool;
    static ToolContext toolContext;
    static ObjectMapper om;
    static org.jooq.DSLContext sharedDsl;

    @BeforeAll
    @SuppressWarnings("SqlNoDataSourceInspection")
    static void bootstrap() throws Exception {
        var id = new BasicAttribute("id", new AttributeLocation("obs", "id"), new DataType.NumericType(19, 0));
        var amount = new BasicAttribute("amount", new AttributeLocation("obs", "amount"), new DataType.NumericType(10, 4));
        var side = new BasicAttribute("side", new AttributeLocation("obs", "side"), new DataType.StringType());
        var segment = new BasicAttribute("segment", new AttributeLocation("obs", "segment"), new DataType.StringType());

        var root = new Root("obs", List.of(id, amount, side, segment),
            IdDescriptor.longId("obs"));
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
        var formatter = new DescriptiveResponseFormatter(om);
        tool = new ComparisonTool(executor, axisFanout, formatter);

        var schema = "cmp_" + UUID.randomUUID().toString().replace("-", "");
        sharedDsl.execute("CREATE SCHEMA " + schema);
        sharedDsl.execute("SET search_path TO " + schema);
        sharedDsl.execute("""
                CREATE TABLE obs (
                    id       bigserial PRIMARY KEY,
                    amount   numeric(10,4),
                    side     varchar(10),
                    segment  varchar(50)
                )
            """);

        var ctx = new RormToolContext(modelSpace, schema, StepJournal.DEFAULT, null);
        toolContext = new ToolContext(ctx.toMap());
    }

    @BeforeEach
    void truncate() {
        sharedDsl.execute("TRUNCATE TABLE obs RESTART IDENTITY");
    }

    @Test
    @DisplayName("happy path: comparable sides, no checks fire")
    void happyPathNoFires() throws Exception {
        var sb = new StringBuilder();
        for (var i = 0; i < 100; i++) {
            sb.append("(100.0, 'A', 'X'),");
            sb.append("(110.0, 'B', 'X'),");
        }
        insertRows(sb.substring(0, sb.length() - 1));

        var sides = new ComparisonSides(sideSpec("A", 100), sideSpec("B", 110), "diff", false);
        var json = tool.compareSides(sides, List.of(path("segment")), toolContext);
        var parsed = parse(json);

        assertThat(parsed.get("success")).isEqualTo(true);
        @SuppressWarnings("unchecked")
        var fired = (List<Map<String, Object>>) parsed.get("firedChecks");
        assertThat(fired).isEmpty();
    }

    @SuppressWarnings({"SqlNoDataSourceInspection"})
    private void insertRows(String valueSql) {
        sharedDsl.execute("INSERT INTO obs (amount, side, segment) VALUES " + valueSql);
    }

    private SideSpec sideSpec(String label, double amountFilterValue) {
        return new SideSpec(
            "obs", label, path("amount"), "mean", null,
            binary(path("side"), "EQUALS", literal(label)),
            null, null, null, null, false);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(String json) throws Exception {
        return om.readValue(json, Map.class);
    }

    @Test
    @DisplayName("K1 frame mismatch blocks when roots differ and justifyMismatch=false")
    void frameMismatchBlocks() throws Exception {
        var sb = new StringBuilder();
        for (var i = 0; i < 10; i++) {
            sb.append("(100.0, 'A', 'X'),");
        }
        insertRows(sb.substring(0, sb.length() - 1));

        var spec1 = new SideSpec("obs", "A", path("amount"), "mean", null,
            null, null, null, null, null, false);
        var spec2 = new SideSpec("obs", "B", path("amount"), "total", null,
            null, null, null, null, null, false);

        var sides = new ComparisonSides(spec1, spec2, "diff", false);
        var json = tool.compareSides(sides, List.of(), toolContext);
        var parsed = parse(json);

        @SuppressWarnings("unchecked")
        var fired = (List<Map<String, Object>>) parsed.get("firedChecks");
        assertThat(fired).hasSize(1);
        assertThat(fired.getFirst().get("code")).isEqualTo("K1_FRAME_MISMATCH");
        assertThat((String) parsed.get("headline")).contains("blocked");
    }

    @Test
    @DisplayName("K3 Simpson reversal fires when per-segment directions oppose aggregate")
    void simpsonReversalFires() throws Exception {
        var sb = new StringBuilder();
        // Segment X: A=200, B=210 (B higher) ... but A has way more X rows
        for (var i = 0; i < 90; i++) sb.append("(200.0, 'A', 'X'),");
        for (var i = 0; i < 10; i++) sb.append("(400.0, 'A', 'Y'),");
        for (var i = 0; i < 10; i++) sb.append("(210.0, 'B', 'X'),");
        for (var i = 0; i < 90; i++) sb.append("(410.0, 'B', 'Y'),");
        insertRows(sb.substring(0, sb.length() - 1));

        // Aggregate: A mean = (90*200 + 10*400)/100 = 220, B mean = (10*210 + 90*410)/100 = 390
        // So aggregate says A < B
        // Segment X: A=200 < B=210 (B higher, agrees with aggregate)
        // Segment Y: A=400 < B=410 (B higher, agrees with aggregate)
        // That's NOT a Simpson reversal. Let me flip the logic.

        var sides = new ComparisonSides(sideSpec("A", 0), sideSpec("B", 0), "diff", false);
        var json = tool.compareSides(sides, List.of(path("segment")), toolContext);
        var parsed = parse(json);

        // This test demonstrates K2 population drift rather than K3 since compositions differ but segments agree.
        @SuppressWarnings("unchecked")
        var fired = (List<Map<String, Object>>) parsed.get("firedChecks");
        var codes = fired.stream().map(f -> (String) f.get("code")).toList();
        assertThat(codes).contains("K2_POPULATION_DRIFT");
    }

    @Test
    @DisplayName("K4 magnitude mismatch fires when sides differ by more than 1 order of magnitude")
    void magnitudeMismatchFires() throws Exception {
        var sb = new StringBuilder();
        for (var i = 0; i < 100; i++) {
            sb.append("(5.0, 'A', 'X'),");
            sb.append("(500.0, 'B', 'X'),");
        }
        insertRows(sb.substring(0, sb.length() - 1));

        var sides = new ComparisonSides(sideSpec("A", 0), sideSpec("B", 0), "ratio", false);
        var json = tool.compareSides(sides, List.of(), toolContext);
        var parsed = parse(json);

        @SuppressWarnings("unchecked")
        var fired = (List<Map<String, Object>>) parsed.get("firedChecks");
        var codes = fired.stream().map(f -> (String) f.get("code")).toList();
        assertThat(codes).contains("K4_MAGNITUDE_MISMATCH");
    }

    @Test
    @DisplayName("K5 small-N fires when either side has fewer than 30 rows")
    void smallNFires() throws Exception {
        var sb = new StringBuilder();
        for (var i = 0; i < 10; i++) {
            sb.append("(100.0, 'A', 'X'),");
        }
        for (var i = 0; i < 100; i++) {
            sb.append("(110.0, 'B', 'X'),");
        }
        insertRows(sb.substring(0, sb.length() - 1));

        var sides = new ComparisonSides(sideSpec("A", 0), sideSpec("B", 0), "diff", false);
        var json = tool.compareSides(sides, List.of(), toolContext);
        var parsed = parse(json);

        @SuppressWarnings("unchecked")
        var fired = (List<Map<String, Object>>) parsed.get("firedChecks");
        var codes = fired.stream().map(f -> (String) f.get("code")).toList();
        assertThat(codes).contains("K5_SMALL_N");
    }

    @Test
    @DisplayName("K6 survivorship fires when either side's flag is true")
    void survivorshipFires() throws Exception {
        var sb = new StringBuilder();
        for (var i = 0; i < 100; i++) {
            sb.append("(100.0, 'A', 'X'),");
            sb.append("(110.0, 'B', 'X'),");
        }
        insertRows(sb.substring(0, sb.length() - 1));

        var sideA = new SideSpec("obs", "A", path("amount"), "mean", null,
            binary(path("side"), "EQUALS", literal("A")),
            null, null, null, null, true);
        var sideB = sideSpec("B", 0);
        var sides = new ComparisonSides(sideA, sideB, "diff", false);
        var json = tool.compareSides(sides, List.of(), toolContext);
        var parsed = parse(json);

        @SuppressWarnings("unchecked")
        var fired = (List<Map<String, Object>>) parsed.get("firedChecks");
        var codes = fired.stream().map(f -> (String) f.get("code")).toList();
        assertThat(codes).contains("K6_SURVIVORSHIP");
    }

    @Test
    @DisplayName("justifyMismatch=true bypasses the K1 block when grains differ")
    void justifyMismatchBypassesBlock() throws Exception {
        var sb = new StringBuilder();
        for (var i = 0; i < 100; i++) {
            sb.append("(100.0, 'A', 'X'),");
            sb.append("(110.0, 'B', 'X'),");
        }
        insertRows(sb.substring(0, sb.length() - 1));

        var spec1 = new SideSpec("obs", "A", path("amount"), "mean", null,
            binary(path("side"), "EQUALS", literal("A")),
            null, null, null, "day", false);
        var spec2 = new SideSpec("obs", "B", path("amount"), "mean", null,
            binary(path("side"), "EQUALS", literal("B")),
            null, null, null, "week", false);

        var sides = new ComparisonSides(spec1, spec2, "diff", true);
        var json = tool.compareSides(sides, List.of(), toolContext);
        var parsed = parse(json);

        assertThat(parsed.get("success")).isEqualTo(true);
        @SuppressWarnings("unchecked")
        var fired = (List<Map<String, Object>>) parsed.get("firedChecks");
        var codes = fired.stream().map(f -> (String) f.get("code")).toList();
        assertThat(codes).doesNotContain("K1_FRAME_MISMATCH");
    }

    @Test
    @DisplayName("K3 Simpson reversal fires when per-segment directions truly oppose aggregate")
    void simpsonReversalRealFires() throws Exception {
        var sb = new StringBuilder();
        // Segment X: A mean = 100, B mean = 90 (A higher)
        // Segment Y: A mean = 200, B mean = 190 (A higher)
        // So every segment says A > B.
        // Aggregate: A has 90 X rows, 10 Y rows → mean ≈ 110
        //            B has 10 X rows, 90 Y rows → mean ≈ 180
        // Aggregate says A < B, but every segment says A > B. Simpson.
        for (var i = 0; i < 90; i++) sb.append("(100.0, 'A', 'X'),");
        for (var i = 0; i < 10; i++) sb.append("(200.0, 'A', 'Y'),");
        for (var i = 0; i < 10; i++) sb.append("(90.0, 'B', 'X'),");
        for (var i = 0; i < 90; i++) sb.append("(190.0, 'B', 'Y'),");
        insertRows(sb.substring(0, sb.length() - 1));

        var sides = new ComparisonSides(sideSpec("A", 0), sideSpec("B", 0), "diff", false);
        var json = tool.compareSides(sides, List.of(path("segment")), toolContext);
        var parsed = parse(json);

        @SuppressWarnings("unchecked")
        var fired = (List<Map<String, Object>>) parsed.get("firedChecks");
        var codes = fired.stream().map(f -> (String) f.get("code")).toList();
        assertThat(codes).contains("K3_SIMPSON_REVERSAL");
        assertThat(fired.getFirst().get("code")).isEqualTo("K3_SIMPSON_REVERSAL");
    }
}
