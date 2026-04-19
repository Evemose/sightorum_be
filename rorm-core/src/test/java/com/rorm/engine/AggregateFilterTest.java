package com.rorm.engine;

import com.rorm.metamodel.*;
import com.rorm.query.*;
import com.rorm.query.Expression.Aggregation;
import com.rorm.query.Expression.BinaryExpression;
import com.rorm.query.Expression.Literal;
import com.rorm.query.Selector.SingleExprSelector;
import com.rorm.testutil.TestHandlerRegistry;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Aggregate FILTER (WHERE ...) clause SQL generation")
class AggregateFilterTest {

    private static Root testRoot;
    private static BasicAttribute statusField;
    private static BasicAttribute amountField;

    private QueryTransformer queryTransformer;

    @BeforeAll
    static void setupMetamodel() {
        statusField = new BasicAttribute("status", new AttributeLocation("orders", "status"), new DataType.StringType());
        amountField = new BasicAttribute("amount", new AttributeLocation("orders", "amount"), new DataType.NumericType(10, 2));
        testRoot = new Root("orders", List.of(statusField, amountField), IdDescriptor.longId("orders"));
    }

    @BeforeEach
    void setUp() {
        var dslContext = DSL.using(SQLDialect.POSTGRES);
        var handlerRegistry = TestHandlerRegistry.createWithAllBuiltIns();
        var expressionTransformer = new ExpressionTransformer(handlerRegistry);
        queryTransformer = new QueryTransformer(dslContext, expressionTransformer);
    }

    @Test
    @DisplayName("SUM with FILTER WHERE produces FILTER clause")
    void sumWithFilter() {
        var sumExpr = Aggregation.of("SUM", new Path(amountField, null))
            .withFilter(BinaryExpression.eq(new Path(statusField, null), new Literal("active")));

        var sql = buildSQL(sumExpr, "active_total");

        assertThat(sql)
            .containsIgnoringCase("sum(")
            .containsIgnoringCase("filter")
            .containsIgnoringCase("where");
    }

    private String buildSQL(Expression expr, String alias) {
        var query = Query.builder()
            .from(AliasedRoot.of(testRoot))
            .selector(new SingleExprSelector(expr, false, alias))
            .build();
        return queryTransformer.transform(query).getSQL();
    }

    @Test
    @DisplayName("COUNT with FILTER WHERE")
    void countWithFilter() {
        var countExpr = Aggregation.count()
            .withFilter(BinaryExpression.gt(new Path(amountField, null), new Literal(100)));

        var sql = buildSQL(countExpr, "high_value_count");

        assertThat(sql)
            .containsIgnoringCase("count(")
            .containsIgnoringCase("filter")
            .containsIgnoringCase("where");
    }

    @Test
    @DisplayName("Aggregation without filter produces no FILTER clause")
    void aggregationWithoutFilter() {
        var sumExpr = Aggregation.of("SUM", new Path(amountField, null));

        var sql = buildSQL(sumExpr, "total");

        assertThat(sql)
            .containsIgnoringCase("sum(")
            .doesNotContainIgnoringCase("filter");
    }

    @Test
    @DisplayName("AVG with FILTER WHERE")
    void avgWithFilter() {
        var avgExpr = new Aggregation("AVG", List.of(new Path(amountField, null)), false)
            .withFilter(BinaryExpression.eq(new Path(statusField, null), new Literal("completed")));

        var sql = buildSQL(avgExpr, "completed_avg");

        assertThat(sql)
            .containsIgnoringCase("avg(")
            .containsIgnoringCase("filter")
            .containsIgnoringCase("where");
    }
}
