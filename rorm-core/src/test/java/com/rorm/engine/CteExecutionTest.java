package com.rorm.engine;

import com.rorm.metamodel.*;
import com.rorm.query.*;
import com.rorm.query.Expression.*;
import com.rorm.query.Join.JoinType;
import com.rorm.query.Selector.MultiExprSelector;
import com.rorm.query.Selector.SingleExprSelector;
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

@DisplayName("CTE query execution")
class CteExecutionTest extends AbstractPostgresTest {

    private static Root orderRoot;
    private static Root cteRoot;
    private static BasicAttribute orderId;
    private static BasicAttribute orderTotal;
    private static BasicAttribute orderStatus;

    private static BasicAttribute cteOrderId;

    private QueryTransformer transformer;

    @BeforeAll
    static void setupMetamodel() {
        orderId = new BasicAttribute("id", new AttributeLocation("orders", "id"), new DataType.NumericType(19, 0));
        orderTotal = new BasicAttribute("total", new AttributeLocation("orders", "total"), new DataType.NumericType(10, 2));
        orderStatus = new BasicAttribute("status", new AttributeLocation("orders", "status"), new DataType.StringType());
        orderRoot = new Root("orders", List.of(orderId, orderTotal, orderStatus), IdDescriptor.longId("orders"));

        cteOrderId = new BasicAttribute("id", new AttributeLocation("active_ids", "id"), new DataType.NumericType(19, 0));
        cteRoot = new Root("active_ids", List.of(cteOrderId), IdDescriptor.longId("active_ids"));
    }

    @Override
    protected void afterDatabaseSetup() {
        var handlerRegistry = TestHandlerRegistry.createWithAllBuiltIns();
        var expressionTransformer = new ExpressionTransformer(handlerRegistry);
        var subqueryTransformer = new SubqueryTransformer(expressionTransformer);
        expressionTransformer.setSubqueryTransformer(subqueryTransformer);
        transformer = new QueryTransformer(dsl, expressionTransformer);

        dsl.execute("""
            create table orders (
                id bigserial primary key,
                total decimal(10,2) not null,
                status varchar(50) not null
            )""");

        dsl.execute("insert into orders (id, total, status) values (1, 100.00, 'active'), (2, 200.00, 'active'), (3, 150.00, 'cancelled'), (4, 50.00, 'active')");
    }

    @Nested
    @DisplayName("CTE as FROM root with pagination")
    class CteAsFrom {

        @Test
        @DisplayName("returns limited rows when CTE is the FROM target")
        void limitedRowsFromCte() {
            var cteQuery = Query.builder()
                .from(AliasedRoot.of(orderRoot))
                .selector(new SingleExprSelector(new Path(orderId, null), false, "id"))
                .where(BinaryExpression.eq(new Path(orderStatus, null), new Literal("active")))
                .build();

            var query = Query.builder()
                .from(AliasedRoot.of(cteRoot, "ao"))
                .selector(new SingleExprSelector(new Path(cteOrderId, null), false, "id"))
                .cte(CteDefinition.of("active_ids", cteQuery, List.of("id")))
                .limit(2L)
                .build();

            var result = dsl.fetch(transformer.transform(query));

            assertThat(result).hasSize(2);
        }
    }

    @Nested
    @DisplayName("CTE as JOIN target with pagination")
    class CteAsJoin {

        @Test
        @DisplayName("joins CTE to real table and returns limited rows")
        void cteJoinedWithLimit() {
            var cteQuery = Query.builder()
                .from(AliasedRoot.of(orderRoot))
                .selector(new SingleExprSelector(new Path(orderId, null), false, "id"))
                .where(BinaryExpression.eq(new Path(orderStatus, null), new Literal("active")))
                .build();

            var cteAlias = AliasedRoot.of(cteRoot, "ao");
            var joinCondition = BinaryExpression.eq(
                new Path(orderId, null),
                new Path(cteOrderId, new Path(cteAlias, null))
            );

            var query = Query.builder()
                .from(AliasedRoot.of(orderRoot))
                .joins(new LinkedHashSet<>(List.of(
                    new Join(cteAlias, JoinType.INNER, joinCondition)
                )))
                .selector(new SingleExprSelector(new Path(orderId, null), false, "id"))
                .cte(CteDefinition.of("active_ids", cteQuery, List.of("id")))
                .limit(10L)
                .build();

            var result = dsl.fetch(transformer.transform(query));

            assertThat(result)
                .hasSize(3)
                .extracting(r -> r.get("id", Long.class))
                .containsExactlyInAnyOrder(1L, 2L, 4L);
        }
    }

    @Nested
    @DisplayName("Multiple CTEs with pagination")
    class MultipleCtes {

        @Test
        @DisplayName("multiple CTEs with LIMIT on outer query return correct rows")
        void multipleCteWithLimit() {
            var cte1 = Query.builder()
                .from(AliasedRoot.of(orderRoot))
                .selector(new SingleExprSelector(new Path(orderId, null), false, "id"))
                .where(BinaryExpression.eq(new Path(orderStatus, null), new Literal("active")))
                .build();

            var cte2 = Query.builder()
                .from(AliasedRoot.of(orderRoot))
                .selector(new SingleExprSelector(
                    Aggregation.of("SUM", new Path(orderTotal, null)), false, "total_sum"))
                .build();

            var query = Query.builder()
                .from(AliasedRoot.of(orderRoot))
                .selector(new SingleExprSelector(new Path(orderId, null), false, "id"))
                .cte(CteDefinition.of("active_ids", cte1))
                .cte(CteDefinition.of("totals", cte2))
                .limit(2L)
                .build();

            var result = dsl.fetch(transformer.transform(query));

            assertThat(result).hasSize(2);
        }
    }

    @Nested
    @DisplayName("CTE with explicit column aliases")
    class CteColumns {

        @Test
        @DisplayName("column aliases map correctly to inner query projections")
        void columnAliasesMappedCorrectly() {
            var cteExprs = new LinkedHashSet<SelectedExpression>();
            cteExprs.add(new SelectedExpression(new Path(orderStatus, null), "s"));
            cteExprs.add(new SelectedExpression(Aggregation.of("COUNT", new Path(orderId, null)), "c"));

            var cteQuery = Query.builder()
                .from(AliasedRoot.of(orderRoot))
                .selector(new MultiExprSelector(cteExprs, false))
                .groupBy(new GroupBy(List.of(new Path(orderStatus, null))))
                .build();

            var statusAttr = new BasicAttribute("order_status", new AttributeLocation("status_counts", "order_status"), new DataType.StringType());
            var countAttr = new BasicAttribute("cnt", new AttributeLocation("status_counts", "cnt"), new DataType.NumericType(19, 0));
            var cteResultRoot = new Root("status_counts", List.of(statusAttr, countAttr), IdDescriptor.longId("status_counts"));

            var mainExprs = new LinkedHashSet<SelectedExpression>();
            mainExprs.add(new SelectedExpression(new Path(statusAttr, null), "order_status"));
            mainExprs.add(new SelectedExpression(new Path(countAttr, null), "cnt"));

            var query = Query.builder()
                .from(AliasedRoot.of(cteResultRoot, "sc"))
                .selector(new MultiExprSelector(mainExprs, false))
                .cte(CteDefinition.of("status_counts", cteQuery, List.of("order_status", "cnt")))
                .build();

            var result = dsl.fetch(transformer.transform(query));

            assertThat(result)
                .extracting(r -> r.get("order_status"), r -> r.get("cnt", Long.class))
                .containsExactlyInAnyOrder(
                    tuple("active", 3L),
                    tuple("cancelled", 1L)
                );
        }
    }

    @Nested
    @DisplayName("CTE visible in nested subquery")
    class CteInSubquery {

        @Test
        @DisplayName("scalar subquery can reference a CTE defined in the outer query")
        void subqueryReferencesCte() {
            var cteQuery = Query.builder()
                .from(AliasedRoot.of(orderRoot))
                .selector(new SingleExprSelector(new Path(orderId, null), false, "id"))
                .where(BinaryExpression.eq(new Path(orderStatus, null), new Literal("active")))
                .build();

            var subquery = new Subquery(Query.builder()
                .from(AliasedRoot.of(cteRoot, "ao"))
                .selector(new SingleExprSelector(
                    Aggregation.of("COUNT", new Path(cteOrderId, null)), false, null))
                .build());

            var mainExprs = new LinkedHashSet<SelectedExpression>();
            mainExprs.add(new SelectedExpression(subquery, "active_count"));

            var query = Query.builder()
                .from(AliasedRoot.of(orderRoot))
                .selector(new MultiExprSelector(mainExprs, false))
                .cte(CteDefinition.of("active_ids", cteQuery, List.of("id")))
                .build();

            var result = dsl.fetch(transformer.transform(query));

            assertThat(result).hasSize(4);
            assertThat(result.getFirst().get("active_count", Long.class)).isEqualTo(3L);
        }
    }
}
