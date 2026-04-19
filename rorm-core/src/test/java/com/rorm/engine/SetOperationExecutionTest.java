package com.rorm.engine;

import com.rorm.metamodel.*;
import com.rorm.query.*;
import com.rorm.query.Expression.*;
import com.rorm.query.Selector.MultiExprSelector;
import com.rorm.testutil.AbstractPostgresTest;
import com.rorm.testutil.TestHandlerRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

@DisplayName("Set operation query execution")
class SetOperationExecutionTest extends AbstractPostgresTest {

    private static Root orderRoot;
    private static BasicAttribute orderId;
    private static BasicAttribute orderStatus;

    private QueryTransformer transformer;

    @BeforeAll
    static void setupMetamodel() {
        orderId = new BasicAttribute("id", new AttributeLocation("orders", "id"), new DataType.NumericType(19, 0));
        orderStatus = new BasicAttribute("status", new AttributeLocation("orders", "status"), new DataType.StringType());
        orderRoot = new Root("orders", List.of(orderId, orderStatus), IdDescriptor.longId("orders"));
    }

    @Override
    protected void afterDatabaseSetup() {
        var handlerRegistry = TestHandlerRegistry.createWithAllBuiltIns();
        var expressionTransformer = new ExpressionTransformer(handlerRegistry);
        transformer = new QueryTransformer(dsl, expressionTransformer);

        dsl.execute("""
            create table orders (
                id bigserial primary key,
                status varchar(50) not null
            )""");

        dsl.execute("insert into orders (id, status) values (1, 'active'), (2, 'active'), (3, 'cancelled'), (4, 'active')");
    }

    @Nested
    @DisplayName("UNION ALL with ORDER BY")
    class UnionAllWithOrderBy {

        @Test
        @DisplayName("returns ordered results from both branches")
        void orderedResultsFromBothBranches() {
            var leftExprs = new LinkedHashSet<SelectedExpression>();
            leftExprs.add(new SelectedExpression(new Path(orderId, null), "id"));
            leftExprs.add(new SelectedExpression(new Path(orderStatus, null), "status"));

            var rightExprs = new LinkedHashSet<SelectedExpression>();
            rightExprs.add(new SelectedExpression(new Path(orderId, null), "id"));
            rightExprs.add(new SelectedExpression(new Path(orderStatus, null), "status"));

            var secondQuery = Query.builder()
                .from(AliasedRoot.of(orderRoot))
                .selector(new MultiExprSelector(rightExprs, false))
                .where(BinaryExpression.eq(new Path(orderStatus, null), new Literal("cancelled")))
                .build();

            var orderByAttr = new BasicAttribute("id", new AttributeLocation("orders", "id"), new DataType.NumericType(19, 0));

            var query = Query.builder()
                .from(AliasedRoot.of(orderRoot))
                .selector(new MultiExprSelector(leftExprs, false))
                .where(BinaryExpression.eq(new Path(orderStatus, null), new Literal("active")))
                .setOperation(SetOperation.unionAll(secondQuery))
                .orderBy(OrderBy.asc(new Path(orderByAttr, null)))
                .build();

            var result = dsl.fetch(transformer.transform(query));

            assertThat(result)
                .hasSize(4)
                .extracting(r -> r.get("id", Long.class), r -> r.get("status"))
                .containsExactly(
                    tuple(1L, "active"),
                    tuple(2L, "active"),
                    tuple(3L, "cancelled"),
                    tuple(4L, "active")
                );
        }

        @Test
        @DisplayName("column values stay in correct positions across branches with different declaration order")
        void columnPositionsCorrectAcrossBranches() {
            var leftExprs = new LinkedHashSet<SelectedExpression>();
            leftExprs.add(new SelectedExpression(new Path(orderId, null), "id"));
            leftExprs.add(new SelectedExpression(new Path(orderStatus, null), "status"));

            var rightExprs = new LinkedHashSet<SelectedExpression>();
            rightExprs.add(new SelectedExpression(new Path(orderStatus, null), "status"));
            rightExprs.add(new SelectedExpression(new Path(orderId, null), "id"));

            var secondQuery = Query.builder()
                .from(AliasedRoot.of(orderRoot))
                .selector(new MultiExprSelector(rightExprs, false))
                .where(BinaryExpression.eq(new Path(orderStatus, null), new Literal("cancelled")))
                .build();

            var query = Query.builder()
                .from(AliasedRoot.of(orderRoot))
                .selector(new MultiExprSelector(leftExprs, false))
                .where(BinaryExpression.eq(new Path(orderStatus, null), new Literal("active")))
                .setOperation(SetOperation.unionAll(secondQuery))
                .build();

            var result = dsl.fetch(transformer.transform(query));

            assertThat(result)
                .extracting(r -> r.get("id", Long.class), r -> r.get("status"))
                .contains(
                    tuple(1L, "active"),
                    tuple(3L, "cancelled")
                );
        }
    }

    @Nested
    @DisplayName("GROUP BY with ordinal literal")
    class GroupByOrdinal {

        @Test
        @DisplayName("GROUP BY literal(1) groups by first projected column")
        void groupByOrdinalLiteral() {
            var exprs = new LinkedHashSet<SelectedExpression>();
            exprs.add(new SelectedExpression(new Path(orderStatus, null), "status"));
            exprs.add(new SelectedExpression(Aggregation.of("COUNT", new Path(orderId, null)), "cnt"));

            var query = Query.builder()
                .from(AliasedRoot.of(orderRoot))
                .selector(new MultiExprSelector(exprs, false))
                .groupBy(new GroupBy(List.of(new Literal(1))))
                .build();

            var result = dsl.fetch(transformer.transform(query));

            assertThat(result)
                .extracting(r -> r.get("status"), r -> r.get("cnt", Long.class))
                .containsExactlyInAnyOrder(
                    tuple("active", 3L),
                    tuple("cancelled", 1L)
                );
        }
    }
}
