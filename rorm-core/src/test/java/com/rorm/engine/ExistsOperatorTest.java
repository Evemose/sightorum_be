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

@DisplayName("EXISTS / NOT EXISTS operator SQL generation")
class ExistsOperatorTest {

    private static Root customerRoot;
    private static Root orderRoot;
    private static BasicAttribute customerId;
    private static BasicAttribute customerName;
    private static BasicAttribute orderId;
    private static BasicAttribute orderCustomerId;
    private static BasicAttribute orderTotal;

    private QueryTransformer queryTransformer;

    @BeforeAll
    static void setupMetamodel() {
        customerId = new BasicAttribute("id", new AttributeLocation("customers", "id"), new DataType.NumericType(19, 0));
        customerName = new BasicAttribute("name", new AttributeLocation("customers", "name"), new DataType.StringType());
        customerRoot = new Root("customers", List.of(customerId, customerName), IdDescriptor.longId("customers"));

        orderId = new BasicAttribute("id", new AttributeLocation("orders", "id"), new DataType.NumericType(19, 0));
        orderCustomerId = new BasicAttribute("customer_id", new AttributeLocation("orders", "customer_id"), new DataType.NumericType(19, 0));
        orderTotal = new BasicAttribute("total", new AttributeLocation("orders", "total"), new DataType.NumericType(10, 2));
        orderRoot = new Root("orders", List.of(orderId, orderCustomerId, orderTotal), IdDescriptor.longId("orders"));
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
    @DisplayName("EXISTS(subquery) in WHERE clause")
    void existsInWhere() {
        // SELECT name FROM customers WHERE EXISTS (SELECT id FROM orders WHERE customer_id = 1)
        var subquery = new Subquery(Query.builder()
            .from(AliasedRoot.of(orderRoot))
            .selector(new SingleExprSelector(new Path(orderId, null), false, null))
            .where(BinaryExpression.eq(new Path(orderCustomerId, null), new Literal(1)))
            .build());

        var existsExpr = UnaryExpression.of(StandardOperator.Unary.EXISTS, subquery);

        var sql = buildWhereSQL(existsExpr);

        assertThat(sql)
            .containsIgnoringCase("exists")
            .containsIgnoringCase("select");
    }

    private String buildWhereSQL(Expression whereExpr) {
        var query = Query.builder()
            .from(AliasedRoot.of(customerRoot))
            .selector(new SingleExprSelector(new Path(customerName, null), false, "name"))
            .where(whereExpr)
            .build();
        return queryTransformer.transform(query).getSQL();
    }

    @Test
    @DisplayName("NOT EXISTS(subquery) in WHERE clause")
    void notExistsInWhere() {
        // SELECT name FROM customers WHERE NOT EXISTS (SELECT id FROM orders WHERE customer_id = 1)
        var subquery = new Subquery(Query.builder()
            .from(AliasedRoot.of(orderRoot))
            .selector(new SingleExprSelector(new Path(orderId, null), false, null))
            .where(BinaryExpression.eq(new Path(orderCustomerId, null), new Literal(1)))
            .build());

        var notExistsExpr = UnaryExpression.not(
            UnaryExpression.of(StandardOperator.Unary.EXISTS, subquery)
        );

        var sql = buildWhereSQL(notExistsExpr);

        assertThat(sql)
            .containsIgnoringCase("not")
            .containsIgnoringCase("exists")
            .containsIgnoringCase("select");
    }

    @Test
    @DisplayName("EXISTS with filtered subquery")
    void existsWithFilteredSubquery() {
        // SELECT name FROM customers WHERE EXISTS (SELECT id FROM orders WHERE total > 100)
        var subquery = new Subquery(Query.builder()
            .from(AliasedRoot.of(orderRoot))
            .selector(new SingleExprSelector(new Path(orderId, null), false, null))
            .where(BinaryExpression.gt(new Path(orderTotal, null), new Literal(100)))
            .build());

        var existsExpr = UnaryExpression.of(StandardOperator.Unary.EXISTS, subquery);

        var sql = buildWhereSQL(existsExpr);

        assertThat(sql)
            .containsIgnoringCase("exists")
            .containsIgnoringCase("orders")
            .containsIgnoringCase("100");
    }

    @Test
    @DisplayName("EXISTS resolves to boolean type")
    void existsResolvesToBoolean() {
        var handlerRegistry = TestHandlerRegistry.createWithAllBuiltIns();
        var typeResolver = new ExpressionTypeResolver(handlerRegistry);

        var subquery = new Subquery(Query.builder()
            .from(AliasedRoot.of(orderRoot))
            .selector(new SingleExprSelector(new Path(orderId, null), false, null))
            .build());

        var existsExpr = UnaryExpression.of(StandardOperator.Unary.EXISTS, subquery);
        var dataType = typeResolver.resolveWithRoot(existsExpr, customerRoot);

        assertThat(dataType).isInstanceOf(DataType.BooleanType.class);
    }
}
