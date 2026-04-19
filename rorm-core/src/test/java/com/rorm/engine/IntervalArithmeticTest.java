package com.rorm.engine;

import com.rorm.metamodel.*;
import com.rorm.query.*;
import com.rorm.query.Expression.*;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("INTERVAL arithmetic SQL generation")
class IntervalArithmeticTest {

    private static Root root;
    private static BasicAttribute createdAt;

    private QueryTransformer queryTransformer;

    @BeforeAll
    static void setupMetamodel() {
        createdAt = new BasicAttribute("created_at", new AttributeLocation("events", "created_at"), new DataType.DateTimeType());
        root = new Root("events", List.of(createdAt), IdDescriptor.longId("events"));
    }

    @BeforeEach
    void setUp() {
        var dslContext = DSL.using(SQLDialect.POSTGRES);
        var handlerRegistry = TestHandlerRegistry.createWithAllBuiltIns();
        var expressionTransformer = new ExpressionTransformer(handlerRegistry);
        queryTransformer = new QueryTransformer(dslContext, expressionTransformer);
    }

    @Test
    @DisplayName("date + INTERVAL days produces date arithmetic SQL")
    void dateAddDays() {
        var interval = FunctionCall.of(StandardFunction.INTERVAL, new Literal(7), new Literal("DAY"));
        var addExpr = BinaryExpression.add(new Path(createdAt, null), interval);

        var query = Query.builder()
            .from(AliasedRoot.of(root))
            .selector(new SingleExprSelector(addExpr, false, "future_date"))
            .build();

        var sql = queryTransformer.transform(query).getSQL();

        assertThat(sql).contains("+");
    }

    @Test
    @DisplayName("date - INTERVAL months produces date arithmetic SQL")
    void dateSubtractMonths() {
        var interval = FunctionCall.of(StandardFunction.INTERVAL, new Literal(3), new Literal("MONTH"));
        var subExpr = BinaryExpression.subtract(new Path(createdAt, null), interval);

        var query = Query.builder()
            .from(AliasedRoot.of(root))
            .selector(new SingleExprSelector(subExpr, false, "past_date"))
            .build();

        var sql = queryTransformer.transform(query).getSQL();

        assertThat(sql).contains("-");
    }

    @Test
    @DisplayName("INTERVAL hours produces valid interval value")
    void intervalHours() {
        var interval = FunctionCall.of(StandardFunction.INTERVAL, new Literal(24), new Literal("HOUR"));
        var addExpr = BinaryExpression.add(new Path(createdAt, null), interval);

        var query = Query.builder()
            .from(AliasedRoot.of(root))
            .selector(new SingleExprSelector(addExpr, false, "next_day"))
            .build();

        var sql = queryTransformer.transform(query).getSQL();

        assertThat(sql).contains("+");
    }

    @Test
    @DisplayName("INTERVAL can be used in WHERE clause for date range")
    void intervalInWhere() {
        var interval = FunctionCall.of(StandardFunction.INTERVAL, new Literal(30), new Literal("DAY"));
        var futureDate = BinaryExpression.add(
            FunctionCall.of(StandardFunction.NOW),
            interval
        );
        var condition = BinaryExpression.lt(new Path(createdAt, null), futureDate);

        var query = Query.builder()
            .from(AliasedRoot.of(root))
            .selector(new SingleExprSelector(new Path(createdAt, null), false, "created_at"))
            .where(condition)
            .build();

        var sql = queryTransformer.transform(query).getSQL();

        assertThat(sql)
            .containsIgnoringCase("where")
            .contains("+")
            .contains("<");
    }

    @Test
    @DisplayName("INTERVAL year produces year-month interval")
    void intervalYear() {
        var interval = FunctionCall.of(StandardFunction.INTERVAL, new Literal(1), new Literal("YEAR"));
        var addExpr = BinaryExpression.add(new Path(createdAt, null), interval);

        var query = Query.builder()
            .from(AliasedRoot.of(root))
            .selector(new SingleExprSelector(addExpr, false, "next_year"))
            .build();

        var sql = queryTransformer.transform(query).getSQL();

        assertThat(sql).contains("+");
    }

    @Test
    @DisplayName("INTERVAL accepts plural unit names")
    void intervalAcceptsPluralUnits() {
        var interval = FunctionCall.of(StandardFunction.INTERVAL, new Literal(3), new Literal("DAYS"));
        var query = Query.builder()
            .from(AliasedRoot.of(root))
            .selector(new SingleExprSelector(interval, false, "days_interval"))
            .build();

        var sql = queryTransformer.transform(query).getSQL();

        assertThat(sql).containsIgnoringCase("interval");
    }
}
