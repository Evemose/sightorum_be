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

@DisplayName("ANY/ALL quantified comparison SQL generation")
class QuantifiedComparisonTest {

    private static Root employeeRoot;
    private static Root departmentRoot;
    private static BasicAttribute empId;
    private static BasicAttribute empSalary;
    private static BasicAttribute empDept;
    private static BasicAttribute deptId;
    private static BasicAttribute deptBudget;

    private QueryTransformer queryTransformer;

    @BeforeAll
    static void setupMetamodel() {
        empId = new BasicAttribute("id", new AttributeLocation("employees", "id"), new DataType.NumericType(19, 0));
        empSalary = new BasicAttribute("salary", new AttributeLocation("employees", "salary"), new DataType.NumericType(10, 2));
        empDept = new BasicAttribute("dept", new AttributeLocation("employees", "dept"), new DataType.StringType());
        employeeRoot = new Root("employees", List.of(empId, empSalary, empDept), IdDescriptor.longId("employees"));

        deptId = new BasicAttribute("id", new AttributeLocation("departments", "id"), new DataType.NumericType(19, 0));
        deptBudget = new BasicAttribute("budget", new AttributeLocation("departments", "budget"), new DataType.NumericType(15, 2));
        departmentRoot = new Root("departments", List.of(deptId, deptBudget), IdDescriptor.longId("departments"));
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
    @DisplayName("= ANY(subquery) produces correct SQL")
    void equalsAny() {
        var subquery = new Subquery(
            Query.builder()
                .from(AliasedRoot.of(departmentRoot))
                .selector(new SingleExprSelector(new Path(deptBudget, null), false, null))
                .build()
        );

        var query = Query.builder()
            .from(AliasedRoot.of(employeeRoot))
            .selector(new SingleExprSelector(new Path(empId, null), false, "id"))
            .where(QuantifiedComparison.any(new Path(empSalary, null), StandardOperator.Binary.EQUALS, subquery))
            .build();

        var sql = queryTransformer.transform(query).getSQL();

        assertThat(sql.toLowerCase())
            .contains("= any");
    }

    @Test
    @DisplayName("= ALL(subquery) produces correct SQL")
    void equalsAll() {
        var subquery = new Subquery(
            Query.builder()
                .from(AliasedRoot.of(departmentRoot))
                .selector(new SingleExprSelector(new Path(deptBudget, null), false, null))
                .build()
        );

        var query = Query.builder()
            .from(AliasedRoot.of(employeeRoot))
            .selector(new SingleExprSelector(new Path(empId, null), false, "id"))
            .where(QuantifiedComparison.all(new Path(empSalary, null), StandardOperator.Binary.EQUALS, subquery))
            .build();

        var sql = queryTransformer.transform(query).getSQL();

        assertThat(sql.toLowerCase())
            .contains("= all");
    }

    @Test
    @DisplayName("> ANY(subquery) produces correct SQL")
    void greaterThanAny() {
        var subquery = new Subquery(
            Query.builder()
                .from(AliasedRoot.of(departmentRoot))
                .selector(new SingleExprSelector(new Path(deptBudget, null), false, null))
                .build()
        );

        var query = Query.builder()
            .from(AliasedRoot.of(employeeRoot))
            .selector(new SingleExprSelector(new Path(empId, null), false, "id"))
            .where(QuantifiedComparison.any(new Path(empSalary, null), StandardOperator.Binary.GREATER_THAN, subquery))
            .build();

        var sql = queryTransformer.transform(query).getSQL();

        assertThat(sql.toLowerCase())
            .contains("> any");
    }

    @Test
    @DisplayName("< ALL(subquery) produces correct SQL")
    void lessThanAll() {
        var subquery = new Subquery(
            Query.builder()
                .from(AliasedRoot.of(departmentRoot))
                .selector(new SingleExprSelector(new Path(deptBudget, null), false, null))
                .build()
        );

        var query = Query.builder()
            .from(AliasedRoot.of(employeeRoot))
            .selector(new SingleExprSelector(new Path(empId, null), false, "id"))
            .where(QuantifiedComparison.all(new Path(empSalary, null), StandardOperator.Binary.LESS_THAN, subquery))
            .build();

        var sql = queryTransformer.transform(query).getSQL();

        assertThat(sql.toLowerCase())
            .contains("< all");
    }

    @Test
    @DisplayName("NOT(= ANY(subquery)) composes correctly")
    void notEqualsAny() {
        var subquery = new Subquery(
            Query.builder()
                .from(AliasedRoot.of(departmentRoot))
                .selector(new SingleExprSelector(new Path(deptBudget, null), false, null))
                .build()
        );

        var query = Query.builder()
            .from(AliasedRoot.of(employeeRoot))
            .selector(new SingleExprSelector(new Path(empId, null), false, "id"))
            .where(UnaryExpression.not(
                QuantifiedComparison.any(new Path(empSalary, null), StandardOperator.Binary.EQUALS, subquery)
            ))
            .build();

        var sql = queryTransformer.transform(query).getSQL();

        assertThat(sql.toLowerCase())
            .contains("not")
            .contains("= any");
    }

    @Test
    @DisplayName("QuantifiedComparison nested in BinaryExpression does not double the comparison")
    void quantifiedInBinaryDoesNotDoubleComparison() {
        var subquery = new Subquery(
            Query.builder()
                .from(AliasedRoot.of(departmentRoot))
                .selector(new SingleExprSelector(new Path(deptBudget, null), false, null))
                .build()
        );

        var quantified = QuantifiedComparison.any(
            new Path(empSalary, null),
            StandardOperator.Binary.GREATER_THAN,
            subquery);

        var query = Query.builder()
            .from(AliasedRoot.of(employeeRoot))
            .selector(new SingleExprSelector(new Path(empId, null), false, "id"))
            .where(new BinaryExpression(new Path(empSalary, null), StandardOperator.Binary.GREATER_THAN.identifier(), quantified))
            .build();

        var sql = queryTransformer.transform(query).getSQL().toLowerCase();
        var whereClause = sql.substring(sql.indexOf("where"));

        var salaryCount = 0;
        var si = 0;
        while ((si = whereClause.indexOf("\"salary\"", si)) >= 0) {
            salaryCount++;
            si += 8;
        }

        assertThat(salaryCount)
            .as("salary should appear once in WHERE, not doubled: %s", whereClause)
            .isEqualTo(1);
    }

    @Test
    @DisplayName("Quantified comparison rejects unsupported operators")
    void unsupportedOperatorThrows() {
        assertThatThrownBy(() -> QuantifiedComparison.any(
            new Path(empSalary, null),
            StandardOperator.Binary.AND,
            new Subquery(
                Query.builder()
                    .from(AliasedRoot.of(departmentRoot))
                    .selector(new SingleExprSelector(new Path(deptBudget, null), false, null))
                    .build()
            )
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unsupported quantified comparison operator");
    }
}
