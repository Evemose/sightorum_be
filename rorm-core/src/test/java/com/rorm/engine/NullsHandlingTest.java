package com.rorm.engine;

import com.rorm.metamodel.*;
import com.rorm.query.*;
import com.rorm.query.Expression.Literal;
import com.rorm.query.OrderBy.NullsHandling;
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

@DisplayName("ORDER BY NULLS FIRST / NULLS LAST SQL generation")
class NullsHandlingTest {

    private static Root testRoot;
    private static BasicAttribute nameField;
    private static BasicAttribute ageField;

    private QueryTransformer queryTransformer;

    @BeforeAll
    static void setupMetamodel() {
        nameField = new BasicAttribute("name", new AttributeLocation("users", "name"), new DataType.StringType());
        ageField = new BasicAttribute("age", new AttributeLocation("users", "age"), new DataType.NumericType(10, 0));
        testRoot = new Root("users", List.of(nameField, ageField), IdDescriptor.longId("users"));
    }

    @BeforeEach
    void setUp() {
        var dslContext = DSL.using(SQLDialect.POSTGRES);
        var handlerRegistry = TestHandlerRegistry.createWithAllBuiltIns();
        var expressionTransformer = new ExpressionTransformer(handlerRegistry);
        queryTransformer = new QueryTransformer(dslContext, expressionTransformer);
    }

    @Test
    @DisplayName("ASC NULLS FIRST")
    void ascNullsFirst() {
        var sql = buildOrderedSQL(OrderBy.asc(new Path(ageField, null), NullsHandling.NULLS_FIRST));

        assertThat(sql)
            .containsIgnoringCase("order by")
            .containsIgnoringCase("asc")
            .containsIgnoringCase("nulls first");
    }

    private String buildOrderedSQL(OrderBy orderBy) {
        var query = Query.builder()
            .from(AliasedRoot.of(testRoot))
            .selector(new SingleExprSelector(new Path(nameField, null), false, "name"))
            .orderBy(orderBy)
            .build();
        return queryTransformer.transform(query).getSQL();
    }

    @Test
    @DisplayName("ASC NULLS LAST")
    void ascNullsLast() {
        var sql = buildOrderedSQL(OrderBy.asc(new Path(ageField, null), NullsHandling.NULLS_LAST));

        assertThat(sql)
            .containsIgnoringCase("order by")
            .containsIgnoringCase("asc")
            .containsIgnoringCase("nulls last");
    }

    @Test
    @DisplayName("DESC NULLS FIRST")
    void descNullsFirst() {
        var sql = buildOrderedSQL(OrderBy.desc(new Path(ageField, null), NullsHandling.NULLS_FIRST));

        assertThat(sql)
            .containsIgnoringCase("order by")
            .containsIgnoringCase("desc")
            .containsIgnoringCase("nulls first");
    }

    @Test
    @DisplayName("DESC NULLS LAST")
    void descNullsLast() {
        var sql = buildOrderedSQL(OrderBy.desc(new Path(ageField, null), NullsHandling.NULLS_LAST));

        assertThat(sql)
            .containsIgnoringCase("order by")
            .containsIgnoringCase("desc")
            .containsIgnoringCase("nulls last");
    }

    @Test
    @DisplayName("Default ordering without nulls handling")
    void defaultOrdering() {
        var sql = buildOrderedSQL(OrderBy.asc(new Path(ageField, null)));

        assertThat(sql)
            .containsIgnoringCase("order by")
            .doesNotContainIgnoringCase("nulls first")
            .doesNotContainIgnoringCase("nulls last");
    }

    @Test
    @DisplayName("withNullsHandling creates copy with nulls placement")
    void withNullsHandling() {
        var original = OrderBy.asc(new Path(ageField, null));
        var withNulls = original.withNullsHandling(NullsHandling.NULLS_FIRST);

        assertThat(original.nullsHandling()).isNull();
        assertThat(withNulls.nullsHandling()).isEqualTo(NullsHandling.NULLS_FIRST);
        assertThat(withNulls.ascending()).isTrue();
    }
}
