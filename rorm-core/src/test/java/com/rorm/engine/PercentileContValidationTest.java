package com.rorm.engine;

import com.rorm.metamodel.*;
import com.rorm.query.Expression.Aggregation;
import com.rorm.query.Expression.FunctionCall;
import com.rorm.query.Expression.Literal;
import com.rorm.query.Path;
import com.rorm.query.Query;
import com.rorm.query.Selector.SingleExprSelector;
import com.rorm.testutil.TestHandlerRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PERCENTILE_CONT validation")
class PercentileContValidationTest {

    private static Root ordersRoot;
    private static BasicAttribute totalAttr;
    private static QueryTransformer transformer;

    @BeforeAll
    static void setup() {
        var id = new BasicAttribute("id", new AttributeLocation("orders", "id"),
            new DataType.NumericType(19, 0));
        totalAttr = new BasicAttribute("total", new AttributeLocation("orders", "total"),
            new DataType.NumericType(10, 2));
        ordersRoot = new Root("orders", List.of(id, totalAttr), IdDescriptor.longId("orders"));

        var registry = TestHandlerRegistry.createWithAllBuiltIns();
        var expressionTransformer = new ExpressionTransformer(registry);
        transformer = new QueryTransformer(null, expressionTransformer, new JoinCollector(expressionTransformer));
    }

    private static Query buildQueryWithPercentile(Aggregation percentile) {
        return Query.builder()
            .from(AliasedRoot.of(ordersRoot))
            .selector(new SingleExprSelector(percentile, false, "p"))
            .build();
    }

    @Nested
    @DisplayName("fraction argument validation")
    class FractionValidation {

        @Test
        @DisplayName("rejects fraction > 1.0")
        void rejectsFractionAboveOne() {
            var percentileExpr = new Aggregation(
                "PERCENTILE_CONT",
                List.of(new Literal(1.5), new Path(totalAttr, null)),
                false
            );
            var query = buildQueryWithPercentile(percentileExpr);

            assertThatThrownBy(() -> transformer.transform(query))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PERCENTILE_CONT fraction must be in [0, 1]")
                .hasMessageContaining("1.5");
        }

        @Test
        @DisplayName("rejects negative fraction")
        void rejectsNegativeFraction() {
            var percentileExpr = new Aggregation(
                "PERCENTILE_CONT",
                List.of(new Literal(-0.1), new Path(totalAttr, null)),
                false
            );
            var query = buildQueryWithPercentile(percentileExpr);

            assertThatThrownBy(() -> transformer.transform(query))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PERCENTILE_CONT fraction must be in [0, 1]")
                .hasMessageContaining("-0.1");
        }

        @Test
        @DisplayName("rejects non-literal fraction expression")
        void rejectsNonLiteralFraction() {
            var totalPath = new Path(totalAttr, null);
            var percentileExpr = new Aggregation(
                "PERCENTILE_CONT",
                List.of(new FunctionCall("ABS", List.of(new Literal(0.5))), totalPath),
                false
            );
            var query = buildQueryWithPercentile(percentileExpr);

            assertThatThrownBy(() -> transformer.transform(query))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PERCENTILE_CONT fraction must be a numeric literal");
        }

        @Test
        @DisplayName("rejects non-numeric literal fraction")
        void rejectsNonNumericLiteral() {
            var totalPath = new Path(totalAttr, null);
            var percentileExpr = new Aggregation(
                "PERCENTILE_CONT",
                List.of(new Literal("half"), totalPath),
                false
            );
            var query = buildQueryWithPercentile(percentileExpr);

            assertThatThrownBy(() -> transformer.transform(query))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PERCENTILE_CONT fraction literal must be numeric");
        }
    }

    @Nested
    @DisplayName("arity validation")
    class ArityValidation {

        @Test
        @DisplayName("rejects zero arguments")
        void rejectsZeroArgs() {
            var percentileExpr = new Aggregation("PERCENTILE_CONT", List.of(), false);
            var query = buildQueryWithPercentile(percentileExpr);

            assertThatThrownBy(() -> transformer.transform(query))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PERCENTILE_CONT requires exactly 2 arguments");
        }

        @Test
        @DisplayName("rejects single argument")
        void rejectsSingleArg() {
            var percentileExpr = new Aggregation(
                "PERCENTILE_CONT",
                List.of(new Literal(0.5)),
                false
            );
            var query = buildQueryWithPercentile(percentileExpr);

            assertThatThrownBy(() -> transformer.transform(query))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PERCENTILE_CONT requires exactly 2 arguments");
        }

        @Test
        @DisplayName("rejects three arguments")
        void rejectsThreeArgs() {
            var totalPath = new Path(totalAttr, null);
            var percentileExpr = new Aggregation(
                "PERCENTILE_CONT",
                List.of(new Literal(0.5), totalPath, new Literal(42)),
                false
            );
            var query = buildQueryWithPercentile(percentileExpr);

            assertThatThrownBy(() -> transformer.transform(query))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PERCENTILE_CONT requires exactly 2 arguments");
        }
    }
}
