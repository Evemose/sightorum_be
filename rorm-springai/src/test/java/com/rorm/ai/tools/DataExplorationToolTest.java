package com.rorm.ai.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rorm.StepJournal;
import com.rorm.ai.RormToolContext;
import com.rorm.engine.ExpressionTypeResolver;
import com.rorm.engine.TestQueryStack;
import com.rorm.mapper.DenseQueryMapper;
import com.rorm.mapper.QueryMapper;
import com.rorm.metamodel.*;
import com.rorm.testutil.TestHandlerRegistry;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.*;
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
@DisplayName("DataExplorationTool")
@org.junit.jupiter.api.parallel.Execution(org.junit.jupiter.api.parallel.ExecutionMode.SAME_THREAD)
class DataExplorationToolTest {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine")
        .withDatabaseName("testdb").withUsername("test").withPassword("test");

    static DataExplorationTool tool;
    static ToolContext toolContext;
    static ObjectMapper om;
    static org.jooq.DSLContext sharedDsl;

    @BeforeAll
    @SuppressWarnings("SqlNoDataSourceInspection")
    static void bootstrap() throws Exception {
        var id = new BasicAttribute("id", new AttributeLocation("obs", "id"), new DataType.NumericType(19, 0));
        var featureA = new BasicAttribute("feature_a", new AttributeLocation("obs", "feature_a"), new DataType.NumericType(10, 4));
        var featureB = new BasicAttribute("feature_b", new AttributeLocation("obs", "feature_b"), new DataType.NumericType(10, 4));
        var outcome = new BasicAttribute("outcome", new AttributeLocation("obs", "outcome"), new DataType.NumericType(10, 4));
        var treatment = new BasicAttribute("treatment", new AttributeLocation("obs", "treatment"), new DataType.NumericType(10, 4));
        var categoryX = new BasicAttribute("category_x", new AttributeLocation("obs", "category_x"), new DataType.StringType());
        var categoryY = new BasicAttribute("category_y", new AttributeLocation("obs", "category_y"), new DataType.StringType());
        var groupVar = new BasicAttribute("group_var", new AttributeLocation("obs", "group_var"), new DataType.StringType());

        var root = new Root("obs", List.of(id, featureA, featureB, outcome, treatment, categoryX, categoryY, groupVar),
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
        var fetcher = TestQueryStack.createFetcher(sharedDsl);

        om = new ObjectMapper();
        var registry = TestHandlerRegistry.createWithAllBuiltIns();
        var typeResolver = new ExpressionTypeResolver(registry);
        var executor = new VerificationQueryExecutor(fetcher, denseQueryMapper, typeResolver);
        tool = new DataExplorationTool(executor, om);

        var schema = "det_" + UUID.randomUUID().toString().replace("-", "");
        sharedDsl.execute("CREATE SCHEMA " + schema);
        sharedDsl.execute("SET search_path TO " + schema);
        sharedDsl.execute("""
                CREATE TABLE obs (
                    id          bigserial PRIMARY KEY,
                    feature_a   numeric(10,4),
                    feature_b   numeric(10,4),
                    outcome     numeric(10,4),
                    treatment   numeric(10,4),
                    category_x  varchar(50),
                    category_y  varchar(50),
                    group_var   varchar(20)
                )
            """);

        var ctx = new RormToolContext(modelSpace, schema, StepJournal.DEFAULT, null);
        toolContext = new ToolContext(ctx.toMap());
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> parse(String json) throws Exception {
        return om.readValue(json, Map.class);
    }

    @BeforeEach
    void truncate() {
        sharedDsl.execute("TRUNCATE TABLE obs RESTART IDENTITY");
    }

    @SuppressWarnings("SqlNoDataSourceInspection")
    void insertRows(String valueSql) {
        sharedDsl.execute("""
                              INSERT INTO obs (feature_a, feature_b, outcome, treatment, category_x, category_y, group_var)
                              VALUES """ + valueSql);
    }

    @Nested
    @DisplayName("stratifiedGradient")
    class StratifiedGradient {

        @Test
        @DisplayName("computes per-group gradient and slope")
        void perGroupGradient() throws Exception {
            var sb = new StringBuilder();
            for (var i = 0; i < 200; i++) {
                var group = (i < 100) ? "A" : "B";
                var a = (i % 100) * 0.1;
                var slope = group.equals("A") ? 0.8 : 0.2;
                var out = a * slope;
                sb.append(String.format("(%s, 0, %s, 0, 'X', 'Y', '%s'),", a, out, group));
            }
            insertRows(sb.substring(0, sb.length() - 1));

            var result = tool.stratifiedGradient(
                "obs", path("feature_a"), path("outcome"), path("group_var"), null, toolContext);
            var parsed = parse(result);

            assertThat(parsed.get("success")).isEqualTo(true);
            assertThat(((Number) parsed.get("total_groups")).intValue()).isEqualTo(2);

            @SuppressWarnings("unchecked")
            var groups = (List<Map<String, Object>>) parsed.get("groups");
            assertThat(groups).hasSize(2);

            var groupA = groups.stream().filter(g -> "A".equals(g.get("category"))).findFirst().orElseThrow();
            var groupB = groups.stream().filter(g -> "B".equals(g.get("category"))).findFirst().orElseThrow();

            assertThat(((Number) groupA.get("slope")).doubleValue()).isCloseTo(0.8, org.assertj.core.data.Offset.offset(0.05));
            assertThat(((Number) groupB.get("slope")).doubleValue()).isCloseTo(0.2, org.assertj.core.data.Offset.offset(0.05));
            assertThat(((Number) groupA.get("gradient")).doubleValue()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.01));
            assertThat(((Number) groupB.get("gradient")).doubleValue()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.01));
            assertThat(((Number) groupA.get("n")).longValue()).isEqualTo(100);
            assertThat(((Number) groupB.get("n")).longValue()).isEqualTo(100);
        }

        @Test
        @DisplayName("rejects non-numeric feature")
        void rejectsNonNumeric() throws Exception {
            insertRows("(1, 0, 1, 0, 'X', 'Y', 'G')");

            var result = tool.stratifiedGradient(
                "obs", path("category_x"), path("outcome"), path("group_var"), null, toolContext);

            assertThat(parse(result).get("success")).isEqualTo(false);
            assertThat((String) parse(result).get("error")).contains("cannot be used in correlation");
        }
    }

    @Nested
    @DisplayName("crossTabulation")
    class CrossTabulation {

        @Test
        @DisplayName("detects equivalent 1:1 mapping between A and B")
        void equivalentMapping() throws Exception {
            var sb = new StringBuilder();
            // low↔cold, high↔hot — 100% concentration, bijective
            for (var i = 0; i < 100; i++) sb.append("(0, 0, 0.2, 0, 'low', 'cold', 'G'),");
            for (var i = 0; i < 100; i++) sb.append("(0, 0, 0.8, 0, 'high', 'hot', 'G'),");
            insertRows(sb.substring(0, sb.length() - 1));

            var result = tool.crossTabulation(
                "obs", path("category_x"), path("category_y"), path("outcome"), null, toolContext);
            var parsed = parse(result);

            assertThat(parsed.get("success")).isEqualTo(true);
            assertThat(((Number) parsed.get("total_n")).longValue()).isEqualTo(200);

            @SuppressWarnings("unchecked")
            var overlap = (Map<String, Object>) parsed.get("overlap");
            assertThat(overlap.get("all_concentrated")).isEqualTo(true);
            assertThat(((Number) overlap.get("near_subset_count")).intValue()).isEqualTo(2);

            @SuppressWarnings("unchecked")
            var concentrations = (List<Map<String, Object>>) overlap.get("concentration_per_a");
            assertThat(concentrations).allSatisfy(c ->
                assertThat(((Number) c.get("concentration")).doubleValue()).isEqualTo(1.0));

            @SuppressWarnings("unchecked")
            var cells = (List<Map<String, Object>>) parsed.get("cells");
            assertThat(cells).hasSize(2)
                .anySatisfy(c -> {
                    assertThat(c.get("a")).isEqualTo("low");
                    assertThat(c.get("b")).isEqualTo("cold");
                    assertThat(((Number) c.get("outcome_rate")).doubleValue()).isCloseTo(0.2, org.assertj.core.data.Offset.offset(0.001));
                })
                .anySatisfy(c -> {
                    assertThat(c.get("a")).isEqualTo("high");
                    assertThat(c.get("b")).isEqualTo("hot");
                    assertThat(((Number) c.get("outcome_rate")).doubleValue()).isCloseTo(0.8, org.assertj.core.data.Offset.offset(0.001));
                });
        }

        @Test
        @DisplayName("detects near-subset: outage maps to one B value, normal spans all")
        void nearSubset() throws Exception {
            var sb = new StringBuilder();
            // "outage" → 95% in "below_50", 5% in "above_50" (near-subset)
            for (var i = 0; i < 95; i++) sb.append("(0, 0, 0.9, 0, 'outage', 'below_50', 'G'),");
            for (var i = 0; i < 5; i++) sb.append("(0, 0, 0.8, 0, 'outage', 'above_50', 'G'),");
            // "normal" → spread across both
            for (var i = 0; i < 50; i++) sb.append("(0, 0, 0.1, 0, 'normal', 'below_50', 'G'),");
            for (var i = 0; i < 50; i++) sb.append("(0, 0, 0.1, 0, 'normal', 'above_50', 'G'),");
            insertRows(sb.substring(0, sb.length() - 1));

            var result = tool.crossTabulation(
                "obs", path("category_x"), path("category_y"), path("outcome"), null, toolContext);
            var parsed = parse(result);

            @SuppressWarnings("unchecked")
            var overlap = (Map<String, Object>) parsed.get("overlap");
            // outage has >90% concentration in one B → 1 near-subset, but normal doesn't
            assertThat(((Number) overlap.get("near_subset_count")).intValue()).isEqualTo(1);
            assertThat(overlap.get("all_concentrated")).isEqualTo(false);

            assertThat(((Number) parsed.get("total_n")).longValue()).isEqualTo(200);

            @SuppressWarnings("unchecked")
            var cells = (List<Map<String, Object>>) parsed.get("cells");
            assertThat(cells).hasSize(4);

            @SuppressWarnings("unchecked")
            var concentrations = (List<Map<String, Object>>) overlap.get("concentration_per_a");
            var outageConc = concentrations.stream()
                .filter(c -> "outage".equals(c.get("a_value"))).findFirst().orElseThrow();
            assertThat(((Number) outageConc.get("concentration")).doubleValue()).isEqualTo(0.95);
            assertThat(outageConc.get("dominant_b")).isEqualTo("below_50");

            var normalConc = concentrations.stream()
                .filter(c -> "normal".equals(c.get("a_value"))).findFirst().orElseThrow();
            assertThat(((Number) normalConc.get("concentration")).doubleValue()).isEqualTo(0.5);
        }

        @Test
        @DisplayName("detects independent variables with high fill rate")
        void independentVariables() throws Exception {
            var sb = new StringBuilder();
            var xs = new String[]{"low", "mid", "high"};
            var ys = new String[]{"cold", "warm", "hot"};
            for (var x : xs) {
                for (var y : ys) {
                    for (var i = 0; i < 30; i++) {
                        sb.append(String.format("(0, 0, 0.5, 0, '%s', '%s', 'G'),", x, y));
                    }
                }
            }
            insertRows(sb.substring(0, sb.length() - 1));

            var result = tool.crossTabulation(
                "obs", path("category_x"), path("category_y"), path("outcome"), null, toolContext);
            var parsed = parse(result);

            @SuppressWarnings("unchecked")
            var overlap = (Map<String, Object>) parsed.get("overlap");
            assertThat(overlap.get("all_concentrated")).isEqualTo(false);
            assertThat(((Number) overlap.get("near_subset_count")).intValue()).isEqualTo(0);
            assertThat(((Number) overlap.get("fill_rate")).doubleValue()).isEqualTo(1.0);
            assertThat(((Number) parsed.get("total_n")).longValue()).isEqualTo(270);
        }
    }

    @Nested
    @DisplayName("thresholdLocation")
    class ThresholdLocation {

        @Test
        @DisplayName("detects inflection point matching claimed threshold")
        void detectsInflection() throws Exception {
            var sb = new StringBuilder();
            // Step function: outcome jumps from 0.2 to 0.8 at feature_a = 5.0
            for (var i = 0; i < 200; i++) {
                var a = i * 0.05;
                var out = a < 5.0 ? 0.2 : 0.8;
                sb.append(String.format("(%s, 0, %s, 0, 'X', 'Y', 'G'),", a, out));
            }
            insertRows(sb.substring(0, sb.length() - 1));

            var result = tool.thresholdLocation(
                "obs", path("feature_a"), path("outcome"), 5.0, 10, null, toolContext);
            var parsed = parse(result);

            assertThat(parsed.get("success")).isEqualTo(true);
            // Step from 0.2 to 0.8 → max_delta should be ~0.6
            assertThat(((Number) parsed.get("max_delta")).doubleValue()).isCloseTo(0.6, org.assertj.core.data.Offset.offset(0.15));
            // Inflection should be near the 5.0 threshold
            assertThat(((Number) parsed.get("claimed_vs_detected_delta")).doubleValue()).isLessThan(1.0);

            @SuppressWarnings("unchecked")
            var bins = (List<Map<String, Object>>) parsed.get("bins");
            assertThat(bins).hasSize(10);
            assertThat(bins.stream().mapToLong(b -> ((Number) b.get("n")).longValue()).sum()).isEqualTo(200);
        }

        @Test
        @DisplayName("reports bins with correct counts")
        void binCounts() throws Exception {
            var sb = new StringBuilder();
            for (var i = 0; i < 100; i++) {
                var a = i * 0.1;
                sb.append(String.format("(%s, 0, %s, 0, 'X', 'Y', 'G'),", a, a * 0.5));
            }
            insertRows(sb.substring(0, sb.length() - 1));

            var result = tool.thresholdLocation(
                "obs", path("feature_a"), path("outcome"), 5.0, 5, null, toolContext);
            var parsed = parse(result);

            @SuppressWarnings("unchecked")
            var bins = (List<Map<String, Object>>) parsed.get("bins");
            assertThat(bins).hasSize(5);

            var totalN = bins.stream().mapToLong(b -> ((Number) b.get("n")).longValue()).sum();
            assertThat(totalN).isEqualTo(100);

            // Linear data: outcome rates should be monotonically increasing
            var rates = bins.stream().map(b -> ((Number) b.get("outcome_rate")).doubleValue()).toList();
            assertThat(rates).isSorted();
        }
    }

    @Nested
    @DisplayName("deploymentDistribution")
    class DeploymentDistribution {

        @Test
        @DisplayName("computes HHI and per-group composition")
        void hhiAndComposition() throws Exception {
            var sb = new StringBuilder();
            // Treatment=1: 90 in hub_A, 10 in hub_B → concentrated (HHI ≈ 0.82)
            for (var i = 0; i < 90; i++) sb.append("(0, 0, 0.5, 1, 'X', 'Y', 'hub_A'),");
            for (var i = 0; i < 10; i++) sb.append("(0, 0, 0.5, 1, 'X', 'Y', 'hub_B'),");
            // Treatment=0: 50 in each → uniform (HHI = 0.5)
            for (var i = 0; i < 50; i++) sb.append("(0, 0, 0.3, 0, 'X', 'Y', 'hub_A'),");
            for (var i = 0; i < 50; i++) sb.append("(0, 0, 0.3, 0, 'X', 'Y', 'hub_B'),");
            insertRows(sb.substring(0, sb.length() - 1));

            var result = tool.deploymentDistribution(
                "obs", path("treatment"), path("group_var"), path("outcome"), null, toolContext);
            var parsed = parse(result);

            assertThat(parsed.get("success")).isEqualTo(true);

            @SuppressWarnings("unchecked")
            var levels = (List<Map<String, Object>>) parsed.get("levels");
            assertThat(levels).hasSize(2);

            // Find treatment=1 level (the concentrated one)
            var treat1 = levels.stream()
                .filter(l -> {
                    var v = l.get("level");
                    return "1".equals(v) || "1.0000".equals(v) || v.toString().startsWith("1");
                })
                .findFirst().orElseThrow();

            assertThat(((Number) treat1.get("total_n")).longValue()).isEqualTo(100);
            // HHI = (90/100)² + (10/100)² = 0.81 + 0.01 = 0.82
            assertThat(((Number) treat1.get("concentration_hhi")).doubleValue()).isCloseTo(0.82, org.assertj.core.data.Offset.offset(0.01));

            @SuppressWarnings("unchecked")
            var treat1Groups = (List<Map<String, Object>>) treat1.get("per_group");
            assertThat(treat1Groups).hasSize(2)
                .anySatisfy(g -> {
                    assertThat(g.get("group")).isEqualTo("hub_A");
                    assertThat(((Number) g.get("n")).longValue()).isEqualTo(90);
                    assertThat(((Number) g.get("pct_of_level")).doubleValue()).isCloseTo(90.0, org.assertj.core.data.Offset.offset(0.1));
                });

            var treat0 = levels.stream()
                .filter(l -> {
                    var v = l.get("level");
                    return "0".equals(v) || "0.0000".equals(v) || v.toString().startsWith("0");
                })
                .findFirst().orElseThrow();

            assertThat(((Number) treat0.get("total_n")).longValue()).isEqualTo(100);
            assertThat(((Number) treat0.get("concentration_hhi")).doubleValue()).isEqualTo(0.5);

            // Marginal outcome rates per group (across all treatment levels)
            // hub_A: (90*0.5 + 50*0.3) / 140 ≈ 0.429,  hub_B: (10*0.5 + 50*0.3) / 60 ≈ 0.333
            @SuppressWarnings("unchecked")
            var marginals = (Map<String, Object>) parsed.get("marginal_outcome_per_group");
            assertThat(marginals).containsKeys("hub_A", "hub_B");
            assertThat(((Number) marginals.get("hub_A")).doubleValue()).isCloseTo(0.429,
                org.assertj.core.data.Offset.offset(0.01));
            assertThat(((Number) marginals.get("hub_B")).doubleValue()).isCloseTo(0.333,
                org.assertj.core.data.Offset.offset(0.01));
        }

        @Test
        @DisplayName("rejects non-numeric outcome")
        void rejectsNonNumericOutcome() throws Exception {
            insertRows("(1, 0, 1, 0, 'X', 'Y', 'G')");

            var result = tool.deploymentDistribution(
                "obs", path("treatment"), path("group_var"), path("category_x"), null, toolContext);

            assertThat(parse(result).get("success")).isEqualTo(false);
        }
    }
}
