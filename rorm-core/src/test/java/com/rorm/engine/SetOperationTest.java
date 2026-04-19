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

import java.util.List;
import java.util.LinkedHashSet;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Set Operations (UNION / INTERSECT / EXCEPT) SQL generation")
class SetOperationTest {

    private static Root orderRoot;
    private static BasicAttribute orderId;
    private static BasicAttribute orderStatus;
    private static BasicAttribute orderCity;
    private static BasicAttribute orderHubId;

    private QueryTransformer queryTransformer;

    @BeforeAll
    static void setupMetamodel() {
        orderId = new BasicAttribute("id", new AttributeLocation("orders", "id"), new DataType.NumericType(19, 0));
        orderStatus = new BasicAttribute("status", new AttributeLocation("orders", "status"), new DataType.StringType());
        orderCity = new BasicAttribute("city", new AttributeLocation("orders", "city"), new DataType.StringType());
        orderHubId = new BasicAttribute("hubId", new AttributeLocation("orders", "hub_id"), new DataType.StringType());
        orderRoot = new Root("orders", List.of(orderId, orderStatus, orderCity, orderHubId), IdDescriptor.longId("orders"));
    }

    @BeforeEach
    void setUp() {
        var dslContext = DSL.using(SQLDialect.POSTGRES);
        var handlerRegistry = TestHandlerRegistry.createWithAllBuiltIns();
        var expressionTransformer = new ExpressionTransformer(handlerRegistry);
        queryTransformer = new QueryTransformer(dslContext, expressionTransformer);
    }

    @Test
    @DisplayName("UNION combines two queries")
    void unionQuery() {
        var secondQuery = Query.builder()
            .from(AliasedRoot.of(orderRoot))
            .selector(new SingleExprSelector(new Path(orderId, null), false, "id"))
            .where(BinaryExpression.eq(new Path(orderStatus, null), new Literal("pending")))
            .build();

        var query = Query.builder()
            .from(AliasedRoot.of(orderRoot))
            .selector(new SingleExprSelector(new Path(orderId, null), false, "id"))
            .where(BinaryExpression.eq(new Path(orderStatus, null), new Literal("active")))
            .setOperation(SetOperation.union(secondQuery))
            .build();

        var sql = queryTransformer.transform(query).getSQL();

        assertThat(sql)
            .containsIgnoringCase("union")
            .doesNotContainIgnoringCase("union all");
    }

    @Test
    @DisplayName("UNION ALL combines two queries with duplicates")
    void unionAllQuery() {
        var secondQuery = Query.builder()
            .from(AliasedRoot.of(orderRoot))
            .selector(new SingleExprSelector(new Path(orderId, null), false, "id"))
            .build();

        var query = Query.builder()
            .from(AliasedRoot.of(orderRoot))
            .selector(new SingleExprSelector(new Path(orderId, null), false, "id"))
            .setOperation(SetOperation.unionAll(secondQuery))
            .build();

        var sql = queryTransformer.transform(query).getSQL();

        assertThat(sql).containsIgnoringCase("union all");
    }

    @Test
    @DisplayName("INTERSECT produces intersection of two queries")
    void intersectQuery() {
        var secondQuery = Query.builder()
            .from(AliasedRoot.of(orderRoot))
            .selector(new SingleExprSelector(new Path(orderId, null), false, "id"))
            .where(BinaryExpression.eq(new Path(orderStatus, null), new Literal("pending")))
            .build();

        var query = Query.builder()
            .from(AliasedRoot.of(orderRoot))
            .selector(new SingleExprSelector(new Path(orderId, null), false, "id"))
            .where(BinaryExpression.eq(new Path(orderStatus, null), new Literal("active")))
            .setOperation(SetOperation.intersect(secondQuery))
            .build();

        var sql = queryTransformer.transform(query).getSQL();

        assertThat(sql).containsIgnoringCase("intersect");
    }

    @Test
    @DisplayName("EXCEPT produces difference of two queries")
    void exceptQuery() {
        var secondQuery = Query.builder()
            .from(AliasedRoot.of(orderRoot))
            .selector(new SingleExprSelector(new Path(orderId, null), false, "id"))
            .where(BinaryExpression.eq(new Path(orderStatus, null), new Literal("cancelled")))
            .build();

        var query = Query.builder()
            .from(AliasedRoot.of(orderRoot))
            .selector(new SingleExprSelector(new Path(orderId, null), false, "id"))
            .setOperation(SetOperation.except(secondQuery))
            .build();

        var sql = queryTransformer.transform(query).getSQL();

        assertThat(sql).containsIgnoringCase("except");
    }

    @Test
    @DisplayName("No set operations produces normal query")
    void noSetOperations() {
        var query = Query.builder()
            .from(AliasedRoot.of(orderRoot))
            .selector(new SingleExprSelector(new Path(orderId, null), false, "id"))
            .build();

        var sql = queryTransformer.transform(query).getSQL();

        assertThat(sql)
            .doesNotContainIgnoringCase("union")
            .doesNotContainIgnoringCase("intersect")
            .doesNotContainIgnoringCase("except");
    }

    @Test
    @DisplayName("UNION keeps deterministic column order across branches")
    void unionDeterministicColumnOrderAcrossBranches() {
        var leftExprs = new LinkedHashSet<SelectedExpression>();
        leftExprs.add(new SelectedExpression(new Path(orderHubId, null), "hubId"));
        leftExprs.add(new SelectedExpression(new Path(orderCity, null), "city"));

        var rightExprs = new LinkedHashSet<SelectedExpression>();
        rightExprs.add(new SelectedExpression(new Path(orderCity, null), "city"));
        rightExprs.add(new SelectedExpression(new Path(orderHubId, null), "hubId"));

        var secondQuery = Query.builder()
            .from(AliasedRoot.of(orderRoot))
            .selector(new MultiExprSelector(rightExprs, false))
            .build();

        var query = Query.builder()
            .from(AliasedRoot.of(orderRoot))
            .selector(new MultiExprSelector(leftExprs, false))
            .setOperation(SetOperation.union(secondQuery))
            .build();

        var sql = queryTransformer.transform(query).getSQL();
        var normalized = sql.toLowerCase().replace("\n", " ").replace("\r", " ");
        var firstHub = normalized.indexOf(" as \"hubid\"");
        var firstCity = normalized.indexOf(" as \"city\"");
        var unionPos = normalized.indexOf(" union ");
        var secondHub = normalized.indexOf(" as \"hubid\"", unionPos);
        var secondCity = normalized.indexOf(" as \"city\"", unionPos);

        assertThat(firstHub).isLessThan(firstCity);
        assertThat(secondHub).isLessThan(secondCity);
    }

    @Test
    @DisplayName("UNION keeps schema qualification for both branches")
    void unionUsesSchemaQualificationInBothBranches() {
        var secondQuery = Query.builder()
            .from(AliasedRoot.of(orderRoot))
            .selector(new SingleExprSelector(new Path(orderId, null), false, "id"))
            .build();

        var query = Query.builder()
            .from(AliasedRoot.of(orderRoot))
            .selector(new SingleExprSelector(new Path(orderId, null), false, "id"))
            .setOperation(SetOperation.union(secondQuery))
            .build();

        var sql = queryTransformer.transform(query, "cold_chain").getSQL();

        assertThat(countOccurrences(sql, "\"cold_chain\".\"orders\"")).isEqualTo(2);
        assertThat(sql).containsIgnoringCase("union");
    }

    private int countOccurrences(String text, String token) {
        var count = 0;
        var fromIndex = 0;
        while (true) {
            var idx = text.indexOf(token, fromIndex);
            if (idx < 0) {
                return count;
            }
            count++;
            fromIndex = idx + token.length();
        }
    }

    @Test
    @DisplayName("set-operation ORDER BY projected literal alias is unqualified")
    void setOperationOrderByProjectedLiteralAliasIsUnqualified() {
        var leftExprs = new LinkedHashSet<SelectedExpression>();
        leftExprs.add(new SelectedExpression(new Literal("left"), "branch"));
        leftExprs.add(new SelectedExpression(new Path(orderId, null), "id"));

        var rightExprs = new LinkedHashSet<SelectedExpression>();
        rightExprs.add(new SelectedExpression(new Literal("right"), "branch"));
        rightExprs.add(new SelectedExpression(new Path(orderId, null), "id"));

        var secondQuery = Query.builder()
            .from(AliasedRoot.of(orderRoot))
            .selector(new MultiExprSelector(rightExprs, false))
            .build();

        var query = Query.builder()
            .from(AliasedRoot.of(orderRoot))
            .selector(new MultiExprSelector(leftExprs, false))
            .setOperation(SetOperation.union(secondQuery))
            .orderBy(OrderBy.asc(new Path(new BasicAttribute("branch", new AttributeLocation("orders", "branch"), new DataType.StringType()), null)))
            .build();

        var sql = queryTransformer.transform(query).getSQL().toLowerCase();

        assertThat(sql).contains("order by \"branch\"");
        assertThat(sql).doesNotContain("order by \"t0_0\".\"branch\"");
    }
}
