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
            // A -> B -> outcome, A has no direct effect on outcome once B is known
            // A = noise, B = A + noise, outcome = B + noise
            var sb = new StringBuilder();
            for (var i = 0; i < 200; i++) {
                var a = i * 0.05;
                var b = a * 0.9 + (i % 3) * 0.01;
                var out = b * 0.95 + (i % 7) * 0.01;
                sb.append(String.format("(%s, %s, %s, 0, 0, 0, 'X', 0, 'active', 0, 'P1', 'G1', 0),", a, b, out));
            }
            insertRows(sb.substring(0, sb.length() - 1));

            var result = tool.verifyScreeningMediation(
                "obs", "feature_a", "mediator_b", "outcome", 0.8, null, toolContext);
            var parsed = parse(result);

            assertThat(parsed.get("success")).isEqualTo(true);
            assertThat(verdict(result)).isEqualTo("SUPPORTED");
            assertThat(material(result)).isFalse();

            var ev = evidence(result);
            assertThat((Number) ev.get("corr_a_b")).satisfies(n ->
                assertThat(n.doubleValue()).isGreaterThan(0.8));
        }

        @Test
        @DisplayName("no mediation — B is unrelated to A")
        void noMediation() throws Exception {
            var sb = new StringBuilder();
            for (var i = 0; i < 200; i++) {
                var a = i * 0.05;
                var b = (200 - i) * 0.03;  // unrelated to A
                var out = a * 0.7 + (i % 5) * 0.01;
                sb.append(String.format("(%s, %s, %s, 0, 0, 0, 'X', 0, 'active', 0, 'P1', 'G1', 0),", a, b, out));
            }
            insertRows(sb.substring(0, sb.length() - 1));

            var result = tool.verifyScreeningMediation(
                "obs", "feature_a", "mediator_b", "outcome", 0.8, null, toolContext);

            assertThat(verdict(result)).isIn("CONTRADICTED", "CONDITIONAL");
            assertThat(material(result)).isTrue();
        }

        @Test
        @DisplayName("partial mediation — B explains some of A's effect")
        void partialMediation() throws Exception {
            var sb = new StringBuilder();
            for (var i = 0; i < 200; i++) {
                var a = i * 0.05;
                var b = a * 0.6 + (i % 3) * 0.3;
                var out = a * 0.3 + b * 0.4 + (i % 7) * 0.02;
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
            // Within each category, continuous_y is noise w.r.t. outcome
            var sb = new StringBuilder();
            var categories = new String[]{"low", "mid", "high"};
            for (var i = 0; i < 300; i++) {
                var cat = categories[i % 3];
                var y = (i * 7 % 100) * 0.01;  // random-ish
                var out = switch (cat) {
                    case "low" -> 0.2 + (i % 5) * 0.001;
                    case "mid" -> 0.5 + (i % 5) * 0.001;
                    default -> 0.8 + (i % 5) * 0.001;
                };
                sb.append(String.format("(0, 0, %s, 0, 0, 0, '%s', %s, 'active', 0, 'P1', 'G1', 0),", out, cat, y));
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
            var sb = new StringBuilder();
            var categories = new String[]{"low", "mid", "high"};
            for (var i = 0; i < 300; i++) {
                var cat = categories[i % 3];
                var y = i * 0.05;
                var out = y * 0.6 + (i % 3) * 0.1;
                sb.append(String.format("(0, 0, %s, 0, 0, 0, '%s', %s, 'active', 0, 'P1', 'G1', 0),", out, cat, y));
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
            var sb = new StringBuilder();
            for (var i = 0; i < 200; i++) {
                var treat = (i % 2 == 0) ? 0 : 1;
                var conf = (i < 100) ? 1 : 2;
                var out = treat * 0.3 + conf * 0.1 + (i % 7) * 0.005;
                sb.append(String.format("(0, 0, %s, %d, %d, 0, 'X', 0, 'active', 0, 'P1', 'G1', 0),", out, treat, conf));
            }
            insertRows(sb.substring(0, sb.length() - 1));

            var result = tool.verifyTreatmentDirection(
                "obs", "treatment", "outcome", "confounder", "positive", 10, null, toolContext);

            assertThat(verdict(result)).isEqualTo("SUPPORTED");
        }

        @Test
        @DisplayName("Simpson's Paradox — direction reverses in strata")
        void simpsonsParadox() throws Exception {
            // Marginally positive, but reverses within each confounder stratum
            var sb = new StringBuilder();
            for (var i = 0; i < 200; i++) {
                double treat, conf, out;
                if (i < 100) {
                    // Stratum 1: mostly treated, high baseline
                    treat = (i % 4 == 0) ? 0 : 1;
                    conf = 1;
                    out = 0.8 - treat * 0.15 + (i % 7) * 0.005;
                } else {
                    // Stratum 2: mostly untreated, low baseline
                    treat = (i % 4 == 0) ? 1 : 0;
                    conf = 2;
                    out = 0.3 - treat * 0.10 + (i % 7) * 0.005;
                }
                sb.append(String.format("(0, 0, %s, %s, %s, 0, 'X', 0, 'active', 0, 'P1', 'G1', 0),", out, treat, conf));
            }
            insertRows(sb.substring(0, sb.length() - 1));

            var result = tool.verifyTreatmentDirection(
                "obs", "treatment", "outcome", "confounder", "positive", 10, null, toolContext);

            assertThat(verdict(result)).isIn("CONDITIONAL", "CONTRADICTED");
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
                var a = i * 0.05;
                var out = a * 0.6 + (i % 5) * 0.01;
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
            // Within groups: negative association (within each group, higher A means lower outcome)
            var sb = new StringBuilder();
            for (var i = 0; i < 300; i++) {
                String group;
                double baseA, baseOut;
                if (i < 100) {
                    group = "G1"; baseA = 1.0; baseOut = 0.2;
                } else if (i < 200) {
                    group = "G2"; baseA = 5.0; baseOut = 0.5;
                } else {
                    group = "G3"; baseA = 9.0; baseOut = 0.8;
                }
                var a = baseA + (i % 100) * 0.02;
                var out = baseOut - (i % 100) * 0.003 + (i % 7) * 0.001;
                sb.append(String.format("(%s, 0, %s, 0, 0, 0, 'X', 0, 'active', 0, 'P1', '%s', 0),", a, out, group));
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
            // B is a genuine confounder (causes both treatment and confounder to correlate)
            // Conditioning on it should reduce or not change their correlation
            var sb = new StringBuilder();
            for (var i = 0; i < 200; i++) {
                var b = (i < 100) ? 1 : 2;
                var treat = b * 0.5 + (i % 13) * 0.02;
                var conf = b * 0.4 + (i % 11) * 0.03;
                var out = treat * 0.3;
                sb.append(String.format("(0, %s, %s, %s, %s, %s, 'X', 0, 'active', 0, 'P1', 'G1', 0),",
                    out, conf, out, treat, conf));
            }
            // Repurposing columns: feature_a=unused, mediator_b=confounder, outcome=out, treatment=treat, confounder=conf
            // Actually let me just use the right columns
            sharedDsl.execute("DELETE FROM obs");

            var sb2 = new StringBuilder();
            for (var i = 0; i < 200; i++) {
                var mediator = (i < 100) ? 1 : 2;
                var treat = mediator * 0.5 + (i % 13) * 0.02;
                var conf = mediator * 0.4 + (i % 11) * 0.03;
                var out = treat * 0.3;
                sb2.append(String.format("(0, %d, %s, %s, %s, 0, 'X', 0, 'active', 0, 'P1', 'G1', 0),",
                    out, mediator, out, treat, conf));
            }
            insertRows(sb2.substring(0, sb2.length() - 1));

            var result = tool.verifyColliderConditioning(
                "obs", "treatment", "confounder", "mediator_b", null, toolContext);

            assertThat(verdict(result)).isIn("SAFE", "NEUTRAL");
        }

        @Test
        @DisplayName("collider warning — conditioning on B increases correlation")
        void colliderDetected() throws Exception {
            // B is a collider: treatment -> B <- confounder
            // Conditioning on B induces spurious correlation between treatment and confounder
            var sb = new StringBuilder();
            for (var i = 0; i < 400; i++) {
                var treat = (i % 20) * 0.1;        // treatment: 0 to 1.9
                var conf = ((i * 7) % 20) * 0.1;   // confounder: independent of treatment
                var b = treat + conf;               // collider: caused by both
                var out = treat * 0.5;
                sb.append(String.format("(0, %s, %s, %s, %s, 0, 'X', 0, 'active', 0, 'P1', 'G1', 0),",
                    out, b, out, treat, conf));
            }
            insertRows(sb.substring(0, sb.length() - 1));

            var result = tool.verifyColliderConditioning(
                "obs", "treatment", "confounder", "mediator_b", null, toolContext);

            // Conditioning on the sum of two independent variables induces negative correlation
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
            // Active units: low outcome rate
            for (var i = 0; i < 150; i++) {
                var out = 0.2 + (i % 5) * 0.01;
                sb.append(String.format("(0, 0, %s, 0, 0, 0, 'X', 0, 'active', 0, 'P1', 'G1', 0),", out));
            }
            // Retired units: high outcome rate (they were removed because they failed)
            for (var i = 0; i < 100; i++) {
                var out = 0.7 + (i % 5) * 0.01;
                sb.append(String.format("(0, 0, %s, 0, 0, 0, 'X', 0, 'retired', 0, 'P1', 'G1', 0),", out));
            }
            insertRows(sb.substring(0, sb.length() - 1));

            var result = tool.verifySurvivorshipBias(
                "obs", "outcome", "status_flag", "retired", null, toolContext);

            assertThat(verdict(result)).isEqualTo("SURVIVORSHIP_CONFIRMED");
            assertThat(material(result)).isTrue();
        }

        @Test
        @DisplayName("survivorship unlikely — no difference between groups")
        void survivorshipUnlikely() throws Exception {
            var sb = new StringBuilder();
            for (var i = 0; i < 150; i++) {
                var out = 0.5 + (i % 7) * 0.005;
                sb.append(String.format("(0, 0, %s, 0, 0, 0, 'X', 0, 'active', 0, 'P1', 'G1', 0),", out));
            }
            for (var i = 0; i < 100; i++) {
                var out = 0.5 + (i % 7) * 0.005;
                sb.append(String.format("(0, 0, %s, 0, 0, 0, 'X', 0, 'retired', 0, 'P1', 'G1', 0),", out));
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
            var sb = new StringBuilder();
            var periods = new String[]{"2020", "2021", "2022"};
            for (var i = 0; i < 300; i++) {
                var period = periods[i % 3];
                var coh = (i % 100);
                var out = coh * 0.01 + (i % 7) * 0.002;
                sb.append(String.format("(0, 0, %s, 0, 0, 0, 'X', 0, 'active', %d, '%s', 'G1', 0),", out, coh, period));
            }
            insertRows(sb.substring(0, sb.length() - 1));

            var result = tool.verifyTemporalConfounding(
                "obs", "cohort", "outcome", "time_period", null, toolContext);

            assertThat(verdict(result)).isEqualTo("SUPPORTED");
        }

        @Test
        @DisplayName("temporal confound — gradient reverses within periods")
        void temporalConfound() throws Exception {
            var sb = new StringBuilder();
            for (var i = 0; i < 300; i++) {
                String period;
                double coh, out;
                if (i < 100) {
                    period = "2020";
                    coh = i;
                    out = 0.8 - coh * 0.005;  // negative slope
                } else if (i < 200) {
                    period = "2021";
                    coh = i - 100;
                    out = 0.6 - coh * 0.004;  // negative slope
                } else {
                    period = "2022";
                    coh = i - 200;
                    out = 0.4 - coh * 0.003;  // negative slope
                }
                sb.append(String.format("(0, 0, %s, 0, 0, 0, 'X', 0, 'active', %s, '%s', 'G1', 0),", out, coh, period));
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
            var sb = new StringBuilder();
            var categories = new String[]{"low", "mid", "high"};
            for (var i = 0; i < 600; i++) {
                var cat = categories[i % 3];
                var out = (i % 3) * 0.3 + (i % 7) * 0.01;
                sb.append(String.format("(0, 0, %s, 0, 0, 0, '%s', 0, 'active', 0, 'P1', 'G1', 0),", out, cat));
            }
            insertRows(sb.substring(0, sb.length() - 1));

            var result = tool.verifySampleSizeAdequacy(
                "obs", "category_x", "outcome", 0.05, null, toolContext);

            assertThat(verdict(result)).isIn("ADEQUATE", "PARTIALLY_ADEQUATE");
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
            // candidate_z is a common cause of both treatment and outcome
            var sb = new StringBuilder();
            for (var i = 0; i < 200; i++) {
                var z = i * 0.05;
                var treat = z * 0.7 + (i % 5) * 0.01;
                var out = z * 0.6 + (i % 7) * 0.01;
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
            assertThat(missing).hasSize(1);
            assertThat(missing.getFirst().get("variable")).isEqualTo("candidate_z");
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
            // treatment=0 group: no correlation between A and outcome
            for (var i = 0; i < 100; i++) {
                var a = i * 0.05;
                var b = (i % 7) * 0.1;
                var out = 0.5 + (i % 11) * 0.002;
                sb.append(String.format("(%s, %s, %s, 0, 0, 0, 'X', 0, 'active', 0, 'P1', 'G1', 0),", a, b, out));
            }
            // treatment=1 group: strong A -> B -> outcome mediation
            for (var i = 0; i < 100; i++) {
                var a = i * 0.05;
                var b = a * 0.9;
                var out = b * 0.95;
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
