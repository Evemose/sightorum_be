package com.rorm.ai.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rorm.StepJournal;
import com.rorm.ai.RormToolContext;
import com.rorm.dto.dense.DenseExpressionDto;
import com.rorm.engine.TestQueryStack;
import com.rorm.mapper.DenseQueryMapper;
import com.rorm.mapper.QueryMapper;
import com.rorm.metamodel.*;
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

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@DisplayName("HypothesisVerificationTool")
@org.junit.jupiter.api.parallel.Execution(org.junit.jupiter.api.parallel.ExecutionMode.SAME_THREAD)
class HypothesisVerificationToolTest {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine")
        .withDatabaseName("testdb").withUsername("test").withPassword("test");

    static HypothesisVerificationTool tool;
    static ToolContext toolContext;
    static ObjectMapper om;
    static org.jooq.DSLContext sharedDsl;
    static String testSchema;

    // ── Metamodel ──

    static Root observationsRoot;
    static ModelSpace modelSpace;

    @BeforeAll
    @SuppressWarnings("SqlNoDataSourceInspection")
    static void bootstrap() throws Exception {
        // Metamodel: single table 'obs' with numeric columns
        var id = new BasicAttribute("id", new AttributeLocation("obs", "id"), new DataType.NumericType(19, 0));
        var featureA = new BasicAttribute("feature_a", new AttributeLocation("obs", "feature_a"), new DataType.NumericType(10, 4));
        var mediatorB = new BasicAttribute("mediator_b", new AttributeLocation("obs", "mediator_b"), new DataType.NumericType(10, 4));
        var outcome = new BasicAttribute("outcome", new AttributeLocation("obs", "outcome"), new DataType.NumericType(10, 4));
        var treatment = new BasicAttribute("treatment", new AttributeLocation("obs", "treatment"), new DataType.NumericType(10, 4));
        var confounder = new BasicAttribute("confounder", new AttributeLocation("obs", "confounder"), new DataType.NumericType(10, 4));
        var modifier = new BasicAttribute("modifier", new AttributeLocation("obs", "modifier"), new DataType.NumericType(10, 4));
        var categoryX = new BasicAttribute("category_x", new AttributeLocation("obs", "category_x"), new DataType.StringType());
        var continuousY = new BasicAttribute("continuous_y", new AttributeLocation("obs", "continuous_y"), new DataType.NumericType(10, 4));
        var statusFlag = new BasicAttribute("status_flag", new AttributeLocation("obs", "status_flag"), new DataType.StringType());
        var cohort = new BasicAttribute("cohort", new AttributeLocation("obs", "cohort"), new DataType.NumericType(10, 4));
        var timePeriod = new BasicAttribute("time_period", new AttributeLocation("obs", "time_period"), new DataType.StringType());
        var groupVar = new BasicAttribute("group_var", new AttributeLocation("obs", "group_var"), new DataType.StringType());
        var candidateZ = new BasicAttribute("candidate_z", new AttributeLocation("obs", "candidate_z"), new DataType.NumericType(10, 4));

        observationsRoot = new Root("obs", List.of(
            id, featureA, mediatorB, outcome, treatment, confounder, modifier,
            categoryX, continuousY, statusFlag, cohort, timePeriod, groupVar, candidateZ
        ), IdDescriptor.longId("obs"));
        modelSpace = new ModelSpace(Set.of(observationsRoot));

        // Wire up DenseQueryMapper -> QueryMapper -> PathResolver (package-private, requires reflection)
        var queryMapper = Mappers.getMapper(QueryMapper.class);
        var pathResolverField = QueryMapper.class.getDeclaredField("pathResolver");
        pathResolverField.setAccessible(true);
        var resolverClass = Class.forName("com.rorm.mapper.PathResolver");
        var resolverCtor = resolverClass.getDeclaredConstructor();
        resolverCtor.setAccessible(true);
        pathResolverField.set(queryMapper, resolverCtor.newInstance());
        var denseQueryMapper = new DenseQueryMapper(queryMapper);

        // Wire up Fetcher via testFixtures utility — use single persistent connection
        var conn = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        sharedDsl = DSL.using(conn);
        var fetcher = TestQueryStack.createFetcher(sharedDsl);

        om = new ObjectMapper();
        tool = new HypothesisVerificationTool(fetcher, om, denseQueryMapper);

        // Create shared test schema and table
        testSchema = "hvt_" + UUID.randomUUID().toString().replace("-", "");
        sharedDsl.execute("CREATE SCHEMA " + testSchema);
        sharedDsl.execute("SET search_path TO " + testSchema);
        sharedDsl.execute("""
            CREATE TABLE obs (
                id          bigserial PRIMARY KEY,
                feature_a   numeric(10,4),
                mediator_b  numeric(10,4),
                outcome     numeric(10,4),
                treatment   numeric(10,4),
                confounder  numeric(10,4),
                modifier    numeric(10,4),
                category_x  varchar(50),
                continuous_y numeric(10,4),
                status_flag varchar(20),
                cohort      numeric(10,4),
                time_period varchar(20),
                group_var   varchar(20),
                candidate_z numeric(10,4)
            )
        """);

        var ctx = new RormToolContext(modelSpace, testSchema, StepJournal.NOOP, null);
        toolContext = new ToolContext(ctx.toMap());
    }

    // ── Helpers ──

    static String verdict(String json) throws Exception {
        return (String) parse(json).get("verdict");
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> parse(String json) throws Exception {
        return om.readValue(json, Map.class);
    }

    static boolean material(String json) throws Exception {
        return (boolean) parse(json).get("material");
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> evidence(String json) throws Exception {
        return (Map<String, Object>) parse(json).get("evidence");
    }

    // ══════════════════════════════════════════════════════════════
    //  Test data insertion — each test gets its own schema
    // ══════════════════════════════════════════════════════════════

    @BeforeEach
    @SuppressWarnings("SqlNoDataSourceInspection")
    void truncateData() {
        sharedDsl.execute("TRUNCATE TABLE obs RESTART IDENTITY");
    }

    @SuppressWarnings("SqlNoDataSourceInspection")
    void insertRows(String valueSql) {
        sharedDsl.execute("""
            INSERT INTO obs (feature_a, mediator_b, outcome, treatment, confounder, modifier,
                             category_x, continuous_y, status_flag, cohort, time_period, group_var, candidate_z)
            VALUES """ + valueSql);
    }

    // ══════════════════════════════════════════════════════════════
    //  Pattern 1: SCREENING / MEDIATION
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Pattern 1: Screening / Mediation")
    class ScreeningMediation {

        @Test
        @DisplayName("full mediation — B completely screens A from outcome")
        void fullMediation() throws Exception {
            // Pure chain: A → B → outcome, no direct A → outcome
            var sb = new StringBuilder();
            for (var i = 0; i < 200; i++) {
                var a = i * 0.1;
                var b = a * 0.9;          // strong A→B
                var out = b * 0.8;        // outcome = f(B) only
                sb.append(String.format("(%s, %s, %s, 0, 0, 0, 'X', 0, 'active', 0, 'P1', 'G1', 0),", a, b, out));
            }
            insertRows(sb.substring(0, sb.length() - 1));

            var result = tool.verifyScreeningMediation(
                "obs", "feature_a", "mediator_b", "outcome", 0.8, null, toolContext);

            assertThat(parse(result).get("success")).isEqualTo(true);
            assertThat(verdict(result)).isEqualTo("SUPPORTED");
            assertThat(material(result)).isFalse();

            var ev = evidence(result);
            assertThat(((Number) ev.get("corr_a_b")).doubleValue()).isGreaterThan(0.9);
        }

        @Test
        @DisplayName("no mediation — B is unrelated to A")
        void noMediation() throws Exception {
            // B is V-shaped (symmetric around midpoint) → CORR(A, B) ≈ 0
            // A → outcome directly, B is noise
            var sb = new StringBuilder();
            for (var i = 0; i < 200; i++) {
                var a = i * 0.1;
                var b = Math.abs(i - 100) * 0.1;   // V-shape: zero linear correlation with i
                var out = a * 0.7;                   // direct A → outcome
                sb.append(String.format("(%s, %s, %s, 0, 0, 0, 'X', 0, 'active', 0, 'P1', 'G1', 0),", a, b, out));
            }
            insertRows(sb.substring(0, sb.length() - 1));

            var result = tool.verifyScreeningMediation(
                "obs", "feature_a", "mediator_b", "outcome", 0.8, null, toolContext);

            assertThat(verdict(result)).isEqualTo("CONTRADICTED");
            assertThat(material(result)).isTrue();

            var ev = evidence(result);
            // V-shaped B has near-zero linear correlation with A
            assertThat(Math.abs(((Number) ev.get("corr_a_b")).doubleValue())).isLessThan(0.1);
        }

        @Test
        @DisplayName("partial mediation — B explains some of A's effect")
        void partialMediation() throws Exception {
            // A → B (imperfect) + A → outcome (direct) + B → outcome
            // The key: B must NOT be a perfect linear transform of A
            var sb = new StringBuilder();
            for (var i = 0; i < 200; i++) {
                var a = i * 0.1;
                // B depends on A plus an independent component (alternating offset)
                var b = a * 0.5 + (i % 2) * 3.0;
                // outcome depends on both A directly and B
                var out = a * 0.3 + b * 0.2;
                sb.append(String.format("(%s, %s, %s, 0, 0, 0, 'X', 0, 'active', 0, 'P1', 'G1', 0),", a, b, out));
            }
            insertRows(sb.substring(0, sb.length() - 1));

            var result = tool.verifyScreeningMediation(
                "obs", "feature_a", "mediator_b", "outcome", 0.8, null, toolContext);

            assertThat(verdict(result)).isIn("INCOMPLETE", "CONDITIONAL");
            assertThat(material(result)).isTrue();
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  Pattern 2: PROXY ABSORPTION
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Pattern 2: Proxy Absorption")
    class ProxyAbsorption {

        @Test
        @DisplayName("complete absorption — Y has no signal within any category of X")
        void completeAbsorption() throws Exception {
            // Within each category, Y is V-shaped around the category midpoint →
            // CORR(Y, outcome) ≈ 0 because V is symmetric.
            // Outcome increases within each category so it has nonzero variance.
            var sb = new StringBuilder();
            var categories = new String[]{"low", "mid", "high"};
            var baselines = new double[]{0.2, 0.5, 0.8};
            for (var i = 0; i < 300; i++) {
                var catIdx = i % 3;
                var withinIdx = i / 3;                               // 0..99 within category
                var y = Math.abs(withinIdx - 50) * 0.1;             // V-shape → zero linear correlation
                var out = baselines[catIdx] + withinIdx * 0.0001;   // tiny monotonic increase
                sb.append(String.format("(0, 0, %s, 0, 0, 0, '%s', %s, 'active', 0, 'P1', 'G1', 0),",
                    out, categories[catIdx], y));
            }
            insertRows(sb.substring(0, sb.length() - 1));

            var result = tool.verifyProxyAbsorption(
                "obs", "category_x", "continuous_y", "outcome", null, toolContext);

            assertThat(verdict(result)).isEqualTo("SUPPORTED");
            assertThat(material(result)).isFalse();
        }

        @Test
        @DisplayName("no absorption — Y retains signal within categories")
        void noAbsorption() throws Exception {
            // Within every category, Y strongly predicts outcome
            var sb = new StringBuilder();
            var categories = new String[]{"low", "mid", "high"};
            for (var i = 0; i < 300; i++) {
                var cat = categories[i % 3];
                var y = (i / 3) * 0.1;           // Y increases monotonically within each category
                var out = y * 0.8;                // outcome = f(Y), strong within-category signal
                sb.append(String.format("(0, 0, %s, 0, 0, 0, '%s', %s, 'active', 0, 'P1', 'G1', 0),",
                    out, cat, y));
            }
            insertRows(sb.substring(0, sb.length() - 1));

            var result = tool.verifyProxyAbsorption(
                "obs", "category_x", "continuous_y", "outcome", null, toolContext);

            assertThat(verdict(result)).isEqualTo("CONTRADICTED");
            assertThat(material(result)).isTrue();
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  Pattern 3: TREATMENT DIRECTION
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Pattern 3: Treatment Direction")
    class TreatmentDirection {

        @Test
        @DisplayName("consistent direction — treatment effect holds across strata")
        void consistentDirection() throws Exception {
            // Treatment always increases outcome, regardless of confounder stratum
            var sb = new StringBuilder();
            for (var i = 0; i < 200; i++) {
                var treat = (i % 2 == 0) ? 0.0 : 1.0;
                var conf = (i < 100) ? 1.0 : 2.0;
                var out = treat * 0.3 + conf * 0.1;
                sb.append(String.format("(0, 0, %s, %s, %s, 0, 'X', 0, 'active', 0, 'P1', 'G1', 0),", out, treat, conf));
            }
            insertRows(sb.substring(0, sb.length() - 1));

            var result = tool.verifyTreatmentDirection(
                "obs", "treatment", "outcome", "confounder", "positive", 10, null, toolContext);

            assertThat(verdict(result)).isEqualTo("SUPPORTED");
            assertThat(material(result)).isFalse();
        }

        @Test
        @DisplayName("Simpson's Paradox — direction reverses in strata")
        void simpsonsParadox() throws Exception {
            // Within each stratum, treatment REDUCES outcome (negative).
            // But treatment is confounded with stratum (high-baseline stratum has more treated).
            var sb = new StringBuilder();
            // Stratum 1 (conf=1, high baseline): 75 treated + 25 untreated
            for (var i = 0; i < 75; i++) {
                sb.append(String.format("(0, 0, %s, 1, 1, 0, 'X', 0, 'active', 0, 'P1', 'G1', 0),", 0.7));
            }
            for (var i = 0; i < 25; i++) {
                sb.append(String.format("(0, 0, %s, 0, 1, 0, 'X', 0, 'active', 0, 'P1', 'G1', 0),", 0.9));
            }
            // Stratum 2 (conf=2, low baseline): 25 treated + 75 untreated
            for (var i = 0; i < 25; i++) {
                sb.append(String.format("(0, 0, %s, 1, 2, 0, 'X', 0, 'active', 0, 'P1', 'G1', 0),", 0.2));
            }
            for (var i = 0; i < 75; i++) {
                sb.append(String.format("(0, 0, %s, 0, 2, 0, 'X', 0, 'active', 0, 'P1', 'G1', 0),", 0.4));
            }
            insertRows(sb.substring(0, sb.length() - 1));

            var result = tool.verifyTreatmentDirection(
                "obs", "treatment", "outcome", "confounder", "positive", 10, null, toolContext);

            // Within both strata, treatment reduces outcome (negative), contradicting claimed "positive".
            // Both strata flip → flippedStrata(2) >= totalValid(2)/2 → CONTRADICTED.
            assertThat(verdict(result)).isEqualTo("CONTRADICTED");
            assertThat(material(result)).isTrue();
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  Pattern 4: EFFECT MODIFIER
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Pattern 4: Effect Modifier")
    class EffectModifier {

        @Test
        @DisplayName("strong effect modification — treatment effect varies >2x across modifier strata")
        void strongModification() throws Exception {
            var sb = new StringBuilder();
            for (var i = 0; i < 400; i++) {
                var treat = i * 0.01;
                var mod = (i < 200) ? 1 : 2;
                // In stratum 1: strong effect. In stratum 2: weak/no effect
                var slope = (mod == 1) ? 0.8 : 0.1;
                var out = treat * slope + (i % 5) * 0.01;
                sb.append(String.format("(0, 0, %s, %s, 0, %d, 'X', 0, 'active', 0, 'P1', 'G1', 0),", out, treat, mod));
            }
            insertRows(sb.substring(0, sb.length() - 1));

            var result = tool.verifyEffectModifier(
                "obs", "treatment", "outcome", "modifier", true, null, toolContext);

            assertThat(verdict(result)).isEqualTo("EMPIRICALLY_SUPPORTED");
            assertThat(material(result)).isTrue();

            var ev = evidence(result);
            assertThat(((Number) ev.get("variance_ratio")).doubleValue()).isGreaterThan(2.0);
        }

        @Test
        @DisplayName("no modification — treatment effect constant across strata")
        void noModification() throws Exception {
            var sb = new StringBuilder();
            for (var i = 0; i < 400; i++) {
                var treat = i * 0.01;
                var mod = (i < 200) ? 1 : 2;
                var out = treat * 0.5 + (i % 5) * 0.01;
                sb.append(String.format("(0, 0, %s, %s, 0, %d, 'X', 0, 'active', 0, 'P1', 'G1', 0),", out, treat, mod));
            }
            insertRows(sb.substring(0, sb.length() - 1));

            var result = tool.verifyEffectModifier(
                "obs", "treatment", "outcome", "modifier", false, null, toolContext);

            assertThat(verdict(result)).isEqualTo("UNSUPPORTED");
            assertThat(material(result)).isFalse();
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  Pattern 5: ABSENCE / BELOW DETECTION
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Pattern 5: Absence / Below Detection")
    class Absence {

        @Test
        @DisplayName("confirmed null — no signal in expected subpopulation")
        void confirmedNull() throws Exception {
            var sb = new StringBuilder();
            for (var i = 0; i < 200; i++) {
                var a = i * 0.05;
                var out = 0.5 + (i % 11) * 0.002;  // outcome is noise, uncorrelated with feature_a
                sb.append(String.format("(%s, 0, %s, 1, 0, 0, 'X', 0, 'active', 0, 'P1', 'G1', 0),", a, out));
            }
            insertRows(sb.substring(0, sb.length() - 1));

            // Filter: treatment = 1 (subpopulation where signal is domain-expected)
            var filter = DenseExpressionDto.binary(
                DenseExpressionDto.path("treatment"),
                "EQUALS",
                DenseExpressionDto.literal(1)
            );

            var result = tool.verifyAbsence(
                "obs", "feature_a", "outcome", filter, "treatment=1", toolContext);

            assertThat(verdict(result)).isEqualTo("CONFIRMED_NULL");
            assertThat(material(result)).isFalse();
        }

        @Test
        @DisplayName("conditional signal exists — subpopulation shows gradient")
        void signalExists() throws Exception {
            var sb = new StringBuilder();
            for (var i = 0; i < 200; i++) {
                var a = i * 0.1;
                var out = a * 0.6;
                sb.append(String.format("(%s, 0, %s, 1, 0, 0, 'X', 0, 'active', 0, 'P1', 'G1', 0),", a, out));
            }
            insertRows(sb.substring(0, sb.length() - 1));

            var filter = DenseExpressionDto.binary(
                DenseExpressionDto.path("treatment"),
                "EQUALS",
                DenseExpressionDto.literal(1)
            );

            var result = tool.verifyAbsence(
                "obs", "feature_a", "outcome", filter, "treatment=1", toolContext);

            assertThat(verdict(result)).isEqualTo("CONDITIONAL_SIGNAL_EXISTS");
            assertThat(material(result)).isTrue();
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  Pattern 6: ECOLOGICAL FALLACY
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Pattern 6: Ecological Fallacy")
    class EcologicalFallacy {

        @Test
        @DisplayName("no fallacy — within-group matches between-group")
        void noFallacy() throws Exception {
            var sb = new StringBuilder();
            var groups = new String[]{"G1", "G2", "G3"};
            for (var i = 0; i < 300; i++) {
                var group = groups[i % 3];
                var a = i * 0.03;
                var out = a * 0.5 + (i % 5) * 0.01;
                sb.append(String.format("(%s, 0, %s, 0, 0, 0, 'X', 0, 'active', 0, 'P1', '%s', 0),", a, out, group));
            }
            insertRows(sb.substring(0, sb.length() - 1));

            var result = tool.verifyEcologicalFallacy(
                "obs", "feature_a", "outcome", "group_var", null, toolContext);

            assertThat(verdict(result)).isEqualTo("SUPPORTED");
            assertThat(material(result)).isFalse();
        }

        @Test
        @DisplayName("ecological fallacy — within-group differs from between-group")
        void fallacyDetected() throws Exception {
            // Between groups: positive association (high-A groups have high outcome)
            // Within groups: negative slope (within each group, higher A → lower outcome)
            var sb = new StringBuilder();
            // G1: A in [1..2], outcome decreases within group (high baseline)
            for (var i = 0; i < 100; i++) {
                var a = 1.0 + i * 0.01;
                var out = 0.3 - i * 0.002;  // negative slope within G1
                sb.append(String.format("(%s, 0, %s, 0, 0, 0, 'X', 0, 'active', 0, 'P1', 'G1', 0),", a, out));
            }
            // G2: A in [4..5], outcome decreases within group (medium baseline)
            for (var i = 0; i < 100; i++) {
                var a = 4.0 + i * 0.01;
                var out = 0.6 - i * 0.002;  // negative slope within G2
                sb.append(String.format("(%s, 0, %s, 0, 0, 0, 'X', 0, 'active', 0, 'P1', 'G2', 0),", a, out));
            }
            // G3: A in [7..8], outcome decreases within group (high baseline)
            for (var i = 0; i < 100; i++) {
                var a = 7.0 + i * 0.01;
                var out = 0.9 - i * 0.002;  // negative slope within G3
                sb.append(String.format("(%s, 0, %s, 0, 0, 0, 'X', 0, 'active', 0, 'P1', 'G3', 0),", a, out));
            }
            insertRows(sb.substring(0, sb.length() - 1));

            var result = tool.verifyEcologicalFallacy(
                "obs", "feature_a", "outcome", "group_var", null, toolContext);

            assertThat(verdict(result)).isEqualTo("ECOLOGICAL");
            assertThat(material(result)).isTrue();
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  Pattern 7: COLLIDER CONDITIONING
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Pattern 7: Collider Conditioning")
    class ColliderConditioning {

        @Test
        @DisplayName("safe — conditioning on B does not increase treatment-confounder correlation")
        void safeConditioning() throws Exception {
            // B is a common cause (not a collider): B → treatment, B → confounder
            // Conditioning on B should reduce or not change their correlation
            var sb = new StringBuilder();
            for (var i = 0; i < 200; i++) {
                var b = (i < 100) ? 1.0 : 2.0;
                var treat = b * 0.5 + i * 0.001;
                var conf = b * 0.4 + i * 0.0005;
                var out = treat * 0.3;
                // columns: feature_a, mediator_b, outcome, treatment, confounder, modifier, ...
                sb.append(String.format("(0, %s, %s, %s, %s, 0, 'X', 0, 'active', 0, 'P1', 'G1', 0),",
                    b, out, treat, conf));
            }
            insertRows(sb.substring(0, sb.length() - 1));

            var result = tool.verifyColliderConditioning(
                "obs", "treatment", "confounder", "mediator_b", null, toolContext);

            assertThat(verdict(result)).isIn("SAFE", "NEUTRAL");
            assertThat(material(result)).isFalse();
        }

        @Test
        @DisplayName("collider warning — conditioning on B increases correlation")
        void colliderDetected() throws Exception {
            // B is a collider: treatment → B ← confounder, where B = treatment + confounder
            // Orthogonal grid ensures treatment and confounder are marginally independent
            var sb = new StringBuilder();
            for (var i = 0; i < 400; i++) {
                var treat = (i % 20) * 0.1;
                var conf = (i / 20) * 0.1;          // orthogonal to treatment
                var b = treat + conf;                 // collider
                var out = treat * 0.5;
                // columns: feature_a, mediator_b, outcome, treatment, confounder, modifier, ...
                sb.append(String.format("(0, %s, %s, %s, %s, 0, 'X', 0, 'active', 0, 'P1', 'G1', 0),",
                    b, out, treat, conf));
            }
            insertRows(sb.substring(0, sb.length() - 1));

            var result = tool.verifyColliderConditioning(
                "obs", "treatment", "confounder", "mediator_b", null, toolContext);

            assertThat(verdict(result)).isEqualTo("COLLIDER_WARNING");
            assertThat(material(result)).isTrue();
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  Pattern 8: SURVIVORSHIP BIAS
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Pattern 8: Survivorship Bias")
    class SurvivorshipBias {

        @Test
        @DisplayName("survivorship confirmed — retired units had worse outcomes")
        void survivorshipConfirmed() throws Exception {
            var sb = new StringBuilder();
            for (var i = 0; i < 150; i++) {
                sb.append("(0, 0, 0.2, 0, 0, 0, 'X', 0, 'active', 0, 'P1', 'G1', 0),");
            }
            for (var i = 0; i < 100; i++) {
                sb.append("(0, 0, 0.7, 0, 0, 0, 'X', 0, 'retired', 0, 'P1', 'G1', 0),");
            }
            insertRows(sb.substring(0, sb.length() - 1));

            var result = tool.verifySurvivorshipBias(
                "obs", "outcome", "status_flag", "retired", null, toolContext);

            assertThat(verdict(result)).isEqualTo("SURVIVORSHIP_CONFIRMED");
            assertThat(material(result)).isTrue();

            var ev = evidence(result);
            assertThat(((Number) ev.get("retired_outcome_rate")).doubleValue()).isEqualByComparingTo(0.7);
            assertThat(((Number) ev.get("active_outcome_rate")).doubleValue()).isEqualByComparingTo(0.2);
            assertThat(((Number) ev.get("retired_n")).longValue()).isEqualTo(100);
            assertThat(((Number) ev.get("active_n")).longValue()).isEqualTo(150);
        }

        @Test
        @DisplayName("survivorship unlikely — no difference between groups")
        void survivorshipUnlikely() throws Exception {
            var sb = new StringBuilder();
            for (var i = 0; i < 150; i++) {
                sb.append("(0, 0, 0.5, 0, 0, 0, 'X', 0, 'active', 0, 'P1', 'G1', 0),");
            }
            for (var i = 0; i < 100; i++) {
                sb.append("(0, 0, 0.5, 0, 0, 0, 'X', 0, 'retired', 0, 'P1', 'G1', 0),");
            }
            insertRows(sb.substring(0, sb.length() - 1));

            var result = tool.verifySurvivorshipBias(
                "obs", "outcome", "status_flag", "retired", null, toolContext);

            assertThat(verdict(result)).isEqualTo("SURVIVORSHIP_UNLIKELY");
            assertThat(material(result)).isFalse();
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  Pattern 9: TEMPORAL CONFOUNDING
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Pattern 9: Temporal Confounding")
    class TemporalConfounding {

        @Test
        @DisplayName("true cohort effect — gradient consistent within periods")
        void trueCohortEffect() throws Exception {
            // Cohort has positive effect on outcome in ALL time periods
            var sb = new StringBuilder();
            var periods = new String[]{"2020", "2021", "2022"};
            for (var i = 0; i < 300; i++) {
                var period = periods[i % 3];
                var coh = (i / 3) * 1.0;         // cohort = 0..99
                var out = coh * 0.01;             // positive slope in all periods
                sb.append(String.format("(0, 0, %s, 0, 0, 0, 'X', 0, 'active', %s, '%s', 'G1', 0),", out, coh, period));
            }
            insertRows(sb.substring(0, sb.length() - 1));

            var result = tool.verifyTemporalConfounding(
                "obs", "cohort", "outcome", "time_period", null, toolContext);

            assertThat(verdict(result)).isEqualTo("SUPPORTED");
            assertThat(material(result)).isFalse();
        }

        @Test
        @DisplayName("temporal confound — gradient reverses within periods")
        void temporalConfound() throws Exception {
            // Overall: higher cohort → higher outcome (because later cohorts are in higher-baseline periods)
            // Within each period: higher cohort → LOWER outcome
            var sb = new StringBuilder();
            for (var i = 0; i < 100; i++) {
                var coh = i * 1.0;
                var out = 0.8 - coh * 0.005;     // negative within-period slope
                sb.append(String.format("(0, 0, %s, 0, 0, 0, 'X', 0, 'active', %s, '2020', 'G1', 0),", out, coh));
            }
            for (var i = 0; i < 100; i++) {
                var coh = 100 + i * 1.0;          // higher cohort numbers in later period
                var out = 1.2 - (coh - 100) * 0.005;
                sb.append(String.format("(0, 0, %s, 0, 0, 0, 'X', 0, 'active', %s, '2021', 'G1', 0),", out, coh));
            }
            for (var i = 0; i < 100; i++) {
                var coh = 200 + i * 1.0;
                var out = 1.6 - (coh - 200) * 0.005;
                sb.append(String.format("(0, 0, %s, 0, 0, 0, 'X', 0, 'active', %s, '2022', 'G1', 0),", out, coh));
            }
            insertRows(sb.substring(0, sb.length() - 1));

            var result = tool.verifyTemporalConfounding(
                "obs", "cohort", "outcome", "time_period", null, toolContext);

            // Overall gradient could be positive (later cohorts in later periods with different baselines),
            // but within each period the gradient is negative
            assertThat(verdict(result)).isIn("TEMPORAL_CONFOUND", "CONDITIONAL");
            assertThat(material(result)).isTrue();
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  Pattern 11: SAMPLE SIZE ADEQUACY
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Pattern 11: Sample Size Adequacy")
    class SampleSizeAdequacy {

        @Test
        @DisplayName("adequate — all strata have sufficient n")
        void adequate() throws Exception {
            // 3 strata, 200 rows each, with meaningful effect sizes
            var sb = new StringBuilder();
            var categories = new String[]{"low", "mid", "high"};
            var baselines = new double[]{0.2, 0.5, 0.8};
            for (var i = 0; i < 600; i++) {
                var catIdx = i % 3;
                var out = baselines[catIdx] + (i / 3) * 0.001; // slight variation to avoid zero variance
                sb.append(String.format("(0, 0, %s, 0, 0, 0, '%s', 0, 'active', 0, 'P1', 'G1', 0),",
                    out, categories[catIdx]));
            }
            insertRows(sb.substring(0, sb.length() - 1));

            var result = tool.verifySampleSizeAdequacy(
                "obs", "category_x", "outcome", 1.0, null, toolContext);

            // 3 strata × 200 rows each, minimumMeaningfulEffect=1.0 → all strata adequate
            assertThat(verdict(result)).isEqualTo("ADEQUATE");
            assertThat(material(result)).isFalse();

            var ev = evidence(result);
            assertThat(((Number) ev.get("adequate_strata")).intValue()).isEqualTo(3);
            assertThat(((Number) ev.get("underpowered_strata")).intValue()).isEqualTo(0);
        }

        @Test
        @DisplayName("underpowered — tiny strata")
        void underpowered() throws Exception {
            var sb = new StringBuilder();
            // 10 categories with only ~5 rows each
            for (var i = 0; i < 50; i++) {
                var cat = "cat" + (i % 10);
                var out = (i % 3) * 0.3 + (i % 7) * 0.01;
                sb.append(String.format("(0, 0, %s, 0, 0, 0, '%s', 0, 'active', 0, 'P1', 'G1', 0),", out, cat));
            }
            insertRows(sb.substring(0, sb.length() - 1));

            var result = tool.verifySampleSizeAdequacy(
                "obs", "category_x", "outcome", 0.05, null, toolContext);

            assertThat(verdict(result)).isEqualTo("UNDERPOWERED");
            assertThat(material(result)).isTrue();
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  Pattern 12: CONFOUNDER COMPLETENESS
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Pattern 12: Confounder Completeness")
    class ConfounderCompleteness {

        @Test
        @DisplayName("complete — no missing confounders")
        void complete() throws Exception {
            // candidate_z is independent of treatment and outcome
            var sb = new StringBuilder();
            for (var i = 0; i < 200; i++) {
                var treat = i * 0.05;
                var out = treat * 0.3 + (i % 7) * 0.01;
                var z = ((i * 13) % 200) * 0.01;  // independent
                sb.append(String.format("(0, 0, %s, %s, 0, 0, 'X', 0, 'active', 0, 'P1', 'G1', %s),", out, treat, z));
            }
            insertRows(sb.substring(0, sb.length() - 1));

            var result = tool.verifyConfounderCompleteness(
                "obs", "treatment", "outcome", List.of("candidate_z"), 0.15, null, toolContext);

            assertThat(verdict(result)).isEqualTo("COMPLETE");
            assertThat(material(result)).isFalse();
        }

        @Test
        @DisplayName("missing confounder — candidate correlates with both treatment and outcome")
        void missingConfounder() throws Exception {
            // Z is a common cause: Z → treatment, Z → outcome
            var sb = new StringBuilder();
            for (var i = 0; i < 200; i++) {
                var z = i * 0.1;
                var treat = z * 0.7;    // strong Z→treatment
                var out = z * 0.6;      // strong Z→outcome
                sb.append(String.format("(0, 0, %s, %s, 0, 0, 'X', 0, 'active', 0, 'P1', 'G1', %s),", out, treat, z));
            }
            insertRows(sb.substring(0, sb.length() - 1));

            var result = tool.verifyConfounderCompleteness(
                "obs", "treatment", "outcome", List.of("candidate_z"), 0.15, null, toolContext);

            assertThat(verdict(result)).isEqualTo("MISSING_CONFOUNDER");
            assertThat(material(result)).isTrue();

            var ev = evidence(result);
            @SuppressWarnings("unchecked")
            var missing = (List<Map<String, Object>>) ev.get("missing_confounders");
            assertThat(missing).hasSize(1)
                .first()
                .satisfies(m -> {
                    assertThat(m.get("variable")).isEqualTo("candidate_z");
                    // Z positively correlated with both → positive bias direction
                    assertThat(m.get("bias_direction")).isEqualTo("positive");
                    assertThat(((Number) m.get("corr_with_treatment")).doubleValue()).isGreaterThan(0.9);
                    assertThat(((Number) m.get("corr_with_outcome")).doubleValue()).isGreaterThan(0.9);
                });
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  Cross-cutting: error handling & filter passthrough
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Cross-cutting concerns")
    class CrossCutting {

        @Test
        @DisplayName("filter expression is applied — restricts data before computation")
        void filterRestrictsData() throws Exception {
            var sb = new StringBuilder();
            // treatment=0: A and outcome are V-shaped / uncorrelated
            for (var i = 0; i < 100; i++) {
                var a = i * 0.1;
                var b = Math.abs(i - 50) * 0.1;
                var out = Math.abs(i - 50) * 0.05;
                sb.append(String.format("(%s, %s, %s, 0, 0, 0, 'X', 0, 'active', 0, 'P1', 'G1', 0),", a, b, out));
            }
            // treatment=1: clean A → B → outcome chain
            for (var i = 0; i < 100; i++) {
                var a = i * 0.1;
                var b = a * 0.9;
                var out = b * 0.8;
                sb.append(String.format("(%s, %s, %s, 1, 0, 0, 'X', 0, 'active', 0, 'P1', 'G1', 0),", a, b, out));
            }
            insertRows(sb.substring(0, sb.length() - 1));

            var filter = DenseExpressionDto.binary(
                DenseExpressionDto.path("treatment"),
                "EQUALS",
                DenseExpressionDto.literal(1)
            );

            var result = tool.verifyScreeningMediation(
                "obs", "feature_a", "mediator_b", "outcome", 0.9, filter, toolContext);

            assertThat(parse(result).get("success")).isEqualTo(true);
            assertThat(verdict(result)).isEqualTo("SUPPORTED");
            assertThat(material(result)).isFalse();
        }

        @Test
        @DisplayName("response always contains success flag and pattern name")
        void responseStructure() throws Exception {
            var sb = new StringBuilder();
            for (var i = 0; i < 50; i++) {
                sb.append(String.format("(%s, %s, %s, 0, 0, 0, 'X', 0, 'active', 0, 'P1', 'G1', 0),",
                    i * 0.1, i * 0.2, i * 0.3));
            }
            insertRows(sb.substring(0, sb.length() - 1));

            var result = tool.verifyScreeningMediation(
                "obs", "feature_a", "mediator_b", "outcome", 0.5, null, toolContext);
            var parsed = parse(result);

            assertThat(parsed).containsKeys("success", "pattern", "verdict", "material",
                "generatorNumber", "skepticNumber", "delta", "executorNote", "evidence");
            assertThat(parsed.get("pattern")).isEqualTo("SCREENING_MEDIATION");
        }
    }
}
