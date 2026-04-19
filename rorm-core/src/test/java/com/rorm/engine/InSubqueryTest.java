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

@DisplayName("IN with subquery SQL generation")
class InSubqueryTest {

    private static Root orderRoot;
    private static Root customerRoot;
    private static BasicAttribute orderId;
    private static BasicAttribute orderCustomerId;
    private static BasicAttribute orderStatus;
    private static BasicAttribute customerId;

    private QueryTransformer queryTransformer;

    @BeforeAll
    static void setupMetamodel() {
        orderId = new BasicAttribute("id", new AttributeLocation("orders", "id"), new DataType.NumericType(19, 0));
        orderCustomerId = new BasicAttribute("customer_id", new AttributeLocation("orders", "customer_id"), new DataType.NumericType(19, 0));
        orderStatus = new BasicAttribute("status", new AttributeLocation("orders", "status"), new DataType.StringType());
        orderRoot = new Root("orders", List.of(orderId, orderCustomerId, orderStatus), IdDescriptor.longId("orders"));

        customerId = new BasicAttribute("id", new AttributeLocation("customers", "id"), new DataType.NumericType(19, 0));
        customerRoot = new Root("customers", List.of(customerId), IdDescriptor.longId("customers"));
    }

    @BeforeEach
    void setUp() {
        var dslContext = DSL.using(SQLDialect.POSTGRES);
        var handlerRegistry = TestHandlerRegistry.createWithAllBuiltIns();
        var expressionTransformer = new ExpressionTransformer(handlerRegistry);
        var subqueryTransformer = new SubqueryTransformer(expressionTransformer);
        expressionTransformer.setSubqueryTransformer(subqueryTransformer);
        queryTransformer = new QueryTransformer(
            dslContext,
            expressionTransformer
        );
    }

    @Test
    @DisplayName("IN with subquery produces correct IN (SELECT ...) SQL")
    void inSubquery() {
        // Subquery: SELECT customer_id FROM orders WHERE status = 'active'
        var subquery = new Subquery(
            Query.builder()
                .from(AliasedRoot.of(orderRoot))
                .selector(new SingleExprSelector(new Path(orderCustomerId, null), false, null))
                .where(BinaryExpression.eq(new Path(orderStatus, null), new Literal("active")))
                .build()
        );

        // Main query: SELECT id FROM customers WHERE id IN (subquery)
        var query = Query.builder()
            .from(AliasedRoot.of(customerRoot))
            .selector(new SingleExprSelector(new Path(customerId, null), false, "id"))
            .where(BinaryExpression.of(new Path(customerId, null), "IN", subquery))
            .build();

        var sql = queryTransformer.transform(query).getSQL();

        assertThat(sql)
            .containsIgnoringCase("in")
            .containsIgnoringCase("select")
            .containsIgnoringCase("from")
            .containsIgnoringCase("where");
    }

    @Test
    @DisplayName("IN with subquery without filter")
    void inSubqueryNoFilter() {
        // Subquery: SELECT customer_id FROM orders
        var subquery = new Subquery(
            Query.builder()
                .from(AliasedRoot.of(orderRoot))
                .selector(new SingleExprSelector(new Path(orderCustomerId, null), false, null))
                .build()
        );

        var query = Query.builder()
            .from(AliasedRoot.of(customerRoot))
            .selector(new SingleExprSelector(new Path(customerId, null), false, "id"))
            .where(BinaryExpression.of(new Path(customerId, null), "IN", subquery))
            .build();

        var sql = queryTransformer.transform(query).getSQL();

        assertThat(sql).containsIgnoringCase("in (select");
    }

    @Test
    @DisplayName("IN with literal list still works")
    void inLiteralList() {
        var query = Query.builder()
            .from(AliasedRoot.of(orderRoot))
            .selector(new SingleExprSelector(new Path(orderId, null), false, "id"))
            .where(BinaryExpression.of(
                new Path(orderStatus, null),
                "IN",
                new Literal(List.of("active", "pending"))
            ))
            .build();

        var sql = queryTransformer.transform(query).getSQL();

        // The IN clause should contain inline values, not a nested SELECT
        var inClauseStart = sql.toLowerCase().indexOf("in (");
        assertThat(inClauseStart).isGreaterThan(0);
        var afterIn = sql.substring(inClauseStart + 4).trim().toLowerCase();
        assertThat(afterIn).doesNotStartWith("select");
    }
}
