package com.rorm.engine;

import com.rorm.metamodel.*;
import com.rorm.query.*;
import com.rorm.query.CaseExpression.WhenClause;
import com.rorm.query.Expression.BinaryExpression;
import com.rorm.query.Expression.Literal;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CASE expression SQL generation")
class CaseExpressionTest {

    private static Root testRoot;
    private static BasicAttribute statusField;
    private static BasicAttribute amountField;
    private static BasicAttribute nameField;

    private QueryTransformer queryTransformer;

    @BeforeAll
    static void setupMetamodel() {
        statusField = new BasicAttribute("status", new AttributeLocation("orders", "status"), new DataType.StringType());
        amountField = new BasicAttribute("amount", new AttributeLocation("orders", "amount"), new DataType.NumericType(10, 2));
        nameField = new BasicAttribute("name", new AttributeLocation("orders", "name"), new DataType.StringType());
        testRoot = new Root("orders", List.of(statusField, amountField, nameField), IdDescriptor.longId("orders"));
    }

    @BeforeEach
    void setUp() {
        var dslContext = DSL.using(SQLDialect.POSTGRES);
        var handlerRegistry = TestHandlerRegistry.createWithAllBuiltIns();
        var expressionTransformer = new ExpressionTransformer(handlerRegistry);
        queryTransformer = new QueryTransformer(dslContext, expressionTransformer);
    }

    @Test
    @DisplayName("CASE with single WHEN and ELSE")
    void caseWithSingleWhenAndElse() {
        var caseExpr = CaseExpression.of(
            List.of(new WhenClause(
                BinaryExpression.eq(new Path(statusField, null), new Literal("active")),
                new Literal("Active Order")
            )),
            new Literal("Other")
        );

        var sql = buildAndGetSQL(caseExpr, "status_label");

        assertThat(sql)
            .containsIgnoringCase("case")
            .containsIgnoringCase("when")
            .containsIgnoringCase("then")
            .containsIgnoringCase("else")
            .containsIgnoringCase("end");
    }

    private String buildAndGetSQL(Expression expr, String alias) {
        var query = Query.builder()
            .from(AliasedRoot.of(testRoot))
            .selector(new SingleExprSelector(expr, false, alias))
            .build();
        return queryTransformer.transform(query).getSQL();
    }

    @Test
    @DisplayName("CASE with multiple WHEN clauses")
    void caseWithMultipleWhens() {
        var statusPath = new Path(statusField, null);
        var caseExpr = CaseExpression.of(
            List.of(
                new WhenClause(BinaryExpression.eq(statusPath, new Literal("active")), new Literal("Active")),
                new WhenClause(BinaryExpression.eq(statusPath, new Literal("pending")), new Literal("Pending")),
                new WhenClause(BinaryExpression.eq(statusPath, new Literal("cancelled")), new Literal("Cancelled"))
            ),
            new Literal("Unknown")
        );

        var sql = buildAndGetSQL(caseExpr, "status_label");

        assertThat(sql)
            .containsIgnoringCase("case")
            .containsIgnoringCase("when")
            .containsIgnoringCase("then")
            .containsIgnoringCase("else");
    }

    @Test
    @DisplayName("CASE without ELSE clause")
    void caseWithoutElse() {
        var caseExpr = CaseExpression.of(
            List.of(new WhenClause(
                BinaryExpression.gt(new Path(amountField, null), new Literal(100)),
                new Literal("High Value")
            ))
        );

        var sql = buildAndGetSQL(caseExpr, "value_category");

        assertThat(sql)
            .containsIgnoringCase("case")
            .containsIgnoringCase("when")
            .containsIgnoringCase("end")
            .doesNotContainIgnoringCase("else");
    }

    @Test
    @DisplayName("CASE with numeric results")
    void caseWithNumericResults() {
        var amountPath = new Path(amountField, null);
        var caseExpr = CaseExpression.of(
            List.of(
                new WhenClause(BinaryExpression.gt(amountPath, new Literal(1000)), new Literal(3)),
                new WhenClause(BinaryExpression.gt(amountPath, new Literal(100)), new Literal(2))
            ),
            new Literal(1)
        );

        var sql = buildAndGetSQL(caseExpr, "tier");

        assertThat(sql)
            .containsIgnoringCase("case")
            .containsIgnoringCase("when")
            .containsIgnoringCase("then");
    }

    @Test
    @DisplayName("CASE in WHERE clause")
    void caseInWhereClause() {
        var caseExpr = CaseExpression.of(
            List.of(new WhenClause(
                BinaryExpression.eq(new Path(statusField, null), new Literal("active")),
                new Literal(1)
            )),
            new Literal(0)
        );

        var query = Query.builder()
            .from(AliasedRoot.of(testRoot))
            .selector(new Selector.RootSelector(testRoot, false))
            .where(BinaryExpression.eq(caseExpr, new Literal(1)))
            .build();

        var sql = queryTransformer.transform(query).getSQL();

        assertThat(sql)
            .containsIgnoringCase("where")
            .containsIgnoringCase("case")
            .containsIgnoringCase("when");
    }

    @Test
    @DisplayName("Nested CASE expressions")
    void nestedCaseExpressions() {
        var innerCase = CaseExpression.of(
            List.of(new WhenClause(
                BinaryExpression.gt(new Path(amountField, null), new Literal(500)),
                new Literal("premium")
            )),
            new Literal("standard")
        );

        var outerCase = CaseExpression.of(
            List.of(new WhenClause(
                BinaryExpression.eq(new Path(statusField, null), new Literal("active")),
                innerCase
            )),
            new Literal("inactive")
        );

        var sql = buildAndGetSQL(outerCase, "category");

        assertThat(sql)
            .containsIgnoringCase("case");
    }
}
