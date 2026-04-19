package com.rorm.engine;

import com.rorm.metamodel.*;
import com.rorm.query.*;
import com.rorm.query.Expression.*;
import com.rorm.query.Selector.MultiExprSelector;
import com.rorm.query.Selector.SingleExprSelector;
import com.rorm.testutil.TestHandlerRegistry;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CTE (WITH clause) SQL generation")
class CteTest {

    private static Root orderRoot;
    private static BasicAttribute orderId;
    private static BasicAttribute orderStatus;
    private static BasicAttribute orderAmount;
    private static Root cteOrderIdsRoot;
    private static BasicAttribute cteId;

    private QueryTransformer queryTransformer;

    @BeforeAll
    static void setupMetamodel() {
        orderId = new BasicAttribute("id", new AttributeLocation("orders", "id"), new DataType.NumericType(19, 0));
        orderStatus = new BasicAttribute("status", new AttributeLocation("orders", "status"), new DataType.StringType());
        orderAmount = new BasicAttribute("amount", new AttributeLocation("orders", "amount"), new DataType.NumericType(10, 2));
        orderRoot = new Root("orders", List.of(orderId, orderStatus, orderAmount), IdDescriptor.longId("orders"));

        cteId = new BasicAttribute("id", new AttributeLocation("order_ids", "id"), new DataType.NumericType(19, 0));
        cteOrderIdsRoot = new Root("order_ids", List.of(cteId), IdDescriptor.longId("order_ids"));
    }

    @BeforeEach
    void setUp() {
        var dslContext = DSL.using(SQLDialect.POSTGRES);
        var handlerRegistry = TestHandlerRegistry.createWithAllBuiltIns();
        var expressionTransformer = new ExpressionTransformer(handlerRegistry);
        queryTransformer = new QueryTransformer(dslContext, expressionTransformer);
    }

    @Test
    @DisplayName("Single CTE produces WITH ... AS (...) SELECT ...")
    void singleCte() {
        // WITH active_orders AS (SELECT id FROM orders WHERE status = 'active')
        // SELECT id FROM orders
        var cteQuery = Query.builder()
            .from(AliasedRoot.of(orderRoot))
            .selector(new SingleExprSelector(new Path(orderId, null), false, "id"))
            .where(BinaryExpression.eq(new Path(orderStatus, null), new Literal("active")))
            .build();

        var query = Query.builder()
            .from(AliasedRoot.of(orderRoot))
            .selector(new SingleExprSelector(new Path(orderId, null), false, "id"))
            .cte(CteDefinition.of("active_orders", cteQuery))
            .build();

        var sql = queryTransformer.transform(query).getSQL();

        assertThat(sql)
            .containsIgnoringCase("with")
            .containsIgnoringCase("active_orders")
            .containsIgnoringCase("as");
    }

    @Test
    @DisplayName("Multiple CTEs produce chained WITH clauses")
    void multipleCtes() {
        var cteQuery1 = Query.builder()
            .from(AliasedRoot.of(orderRoot))
            .selector(new SingleExprSelector(new Path(orderId, null), false, "id"))
            .where(BinaryExpression.eq(new Path(orderStatus, null), new Literal("active")))
            .build();

        var cteQuery2 = Query.builder()
            .from(AliasedRoot.of(orderRoot))
            .selector(new SingleExprSelector(
                Aggregation.of("SUM", new Path(orderAmount, null)), false, "total"))
            .build();

        var query = Query.builder()
            .from(AliasedRoot.of(orderRoot))
            .selector(new SingleExprSelector(new Path(orderId, null), false, "id"))
            .cte(CteDefinition.of("active_orders", cteQuery1))
            .cte(CteDefinition.of("order_totals", cteQuery2))
            .build();

        var sql = queryTransformer.transform(query).getSQL();

        assertThat(sql)
            .containsIgnoringCase("with")
            .containsIgnoringCase("active_orders")
            .containsIgnoringCase("order_totals");
    }

    @Test
    @DisplayName("CTE with explicit column aliases")
    void cteWithColumnAliases() {
        var cteQuery = Query.builder()
            .from(AliasedRoot.of(orderRoot))
            .selector(new SingleExprSelector(new Path(orderId, null), false, "id"))
            .build();

        var query = Query.builder()
            .from(AliasedRoot.of(orderRoot))
            .selector(new SingleExprSelector(new Path(orderId, null), false, "id"))
            .cte(CteDefinition.of("my_cte", cteQuery, List.of("order_id")))
            .build();

        var sql = queryTransformer.transform(query).getSQL();

        assertThat(sql)
            .containsIgnoringCase("with")
            .containsIgnoringCase("my_cte")
            .containsIgnoringCase("order_id");
    }

    @Test
    @DisplayName("Query without CTEs produces no WITH clause")
    void noCtes() {
        var query = Query.builder()
            .from(AliasedRoot.of(orderRoot))
            .selector(new SingleExprSelector(new Path(orderId, null), false, "id"))
            .build();

        var sql = queryTransformer.transform(query).getSQL();

        assertThat(sql).doesNotContainIgnoringCase("with");
    }

    @Test
    @DisplayName("CTE can be used as FROM root")
    void cteAsFromRoot() {
        var cteQuery = Query.builder()
            .from(AliasedRoot.of(orderRoot))
            .selector(new SingleExprSelector(new Path(orderId, null), false, "id"))
            .build();

        var query = Query.builder()
            .from(AliasedRoot.of(cteOrderIdsRoot, "oi"))
            .selector(new SingleExprSelector(new Path(cteId, null), false, "id"))
            .cte(CteDefinition.of("order_ids", cteQuery, List.of("id")))
            .build();

        var sql = queryTransformer.transform(query).getSQL();

        assertThat(sql)
            .containsIgnoringCase("with")
            .containsIgnoringCase("order_ids")
            .containsIgnoringCase("from \"order_ids\"");
    }

    @Test
    @DisplayName("CTE can be used as explicit JOIN target")
    void cteAsJoinTarget() {
        var cteQuery = Query.builder()
            .from(AliasedRoot.of(orderRoot))
            .selector(new SingleExprSelector(new Path(orderId, null), false, "id"))
            .build();

        var joinCondition = BinaryExpression.eq(new Path(orderId, null), new Path(cteId, new Path(AliasedRoot.of(cteOrderIdsRoot, "oi"), null)));
        var query = Query.builder()
            .from(AliasedRoot.of(orderRoot))
            .joins(new LinkedHashSet<>(List.of(
                new Join(AliasedRoot.of(cteOrderIdsRoot, "oi"), Join.JoinType.INNER, joinCondition)
            )))
            .selector(new SingleExprSelector(new Path(orderId, null), false, "id"))
            .cte(CteDefinition.of("order_ids", cteQuery, List.of("id")))
            .build();

        var sql = queryTransformer.transform(query).getSQL();

        assertThat(sql)
            .containsIgnoringCase("join \"order_ids\"")
            .containsIgnoringCase("with");
    }

    @Test
    @DisplayName("CTE names are never schema-qualified in schema mode")
    void cteNameNotSchemaQualified() {
        var cteQuery = Query.builder()
            .from(AliasedRoot.of(orderRoot))
            .selector(new SingleExprSelector(new Path(orderId, null), false, "id"))
            .build();

        var query = Query.builder()
            .from(AliasedRoot.of(cteOrderIdsRoot, "oi"))
            .selector(new SingleExprSelector(new Path(cteId, null), false, "id"))
            .cte(CteDefinition.of("order_ids", cteQuery, List.of("id")))
            .build();

        var sql = queryTransformer.transform(query, "cold_chain").getSQL();

        assertThat(sql)
            .contains("\"cold_chain\".\"orders\"")
            .contains("from \"order_ids\"")
            .doesNotContain("\"cold_chain\".\"order_ids\"");
    }

    @Test
    @DisplayName("CTE name is not schema-qualified when used as explicit JOIN target")
    void cteJoinTargetNotSchemaQualified() {
        var cteQuery = Query.builder()
            .from(AliasedRoot.of(orderRoot))
            .selector(new SingleExprSelector(new Path(orderId, null), false, "id"))
            .where(BinaryExpression.eq(new Path(orderStatus, null), new Literal("active")))
            .build();

        var cteAlias = AliasedRoot.of(cteOrderIdsRoot, "oi");
        var joinCondition = BinaryExpression.eq(
            new Path(orderId, null),
            new Path(cteId, new Path(cteAlias, null))
        );

        var query = Query.builder()
            .from(AliasedRoot.of(orderRoot))
            .joins(new LinkedHashSet<>(List.of(
                new Join(cteAlias, Join.JoinType.INNER, joinCondition)
            )))
            .selector(new SingleExprSelector(new Path(orderId, null), false, "id"))
            .cte(CteDefinition.of("order_ids", cteQuery, List.of("id")))
            .limit(10L)
            .build();

        var sql = queryTransformer.transform(query, "cold_chain").getSQL();

        assertThat(sql)
            .contains("\"cold_chain\".\"orders\"")
            .doesNotContain("\"cold_chain\".\"order_ids\"");
    }

    @Test
    @DisplayName("CTE name is not schema-qualified inside a scalar subquery")
    void cteNotSchemaQualifiedInSubquery() {
        var cteQuery = Query.builder()
            .from(AliasedRoot.of(orderRoot))
            .selector(new SingleExprSelector(new Path(orderId, null), false, "id"))
            .where(BinaryExpression.eq(new Path(orderStatus, null), new Literal("active")))
            .build();

        var subquery = new Subquery(Query.builder()
            .from(AliasedRoot.of(cteOrderIdsRoot, "oi"))
            .selector(new SingleExprSelector(
                Aggregation.of("COUNT", new Path(cteId, null)), false, null))
            .build());

        var mainExprs = new LinkedHashSet<SelectedExpression>();
        mainExprs.add(new SelectedExpression(new Path(orderId, null), "id"));
        mainExprs.add(new SelectedExpression(subquery, "active_count"));

        var handlerRegistry = TestHandlerRegistry.createWithAllBuiltIns();
        var exprTransformer = new ExpressionTransformer(handlerRegistry);
        var subqTransformer = new SubqueryTransformer(exprTransformer);
        exprTransformer.setSubqueryTransformer(subqTransformer);
        var qt = new QueryTransformer(DSL.using(SQLDialect.POSTGRES), exprTransformer);

        var query = Query.builder()
            .from(AliasedRoot.of(orderRoot))
            .selector(new MultiExprSelector(mainExprs, false))
            .cte(CteDefinition.of("order_ids", cteQuery, List.of("id")))
            .build();

        var sql = qt.transform(query, "cold_chain").getSQL();

        assertThat(sql)
            .contains("\"cold_chain\".\"orders\"")
            .doesNotContain("\"cold_chain\".\"order_ids\"");
    }

}
