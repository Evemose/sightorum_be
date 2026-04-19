package com.rorm.engine;

import com.rorm.metamodel.*;
import com.rorm.query.Expression.FunctionCall;
import com.rorm.query.Expression.Literal;
import com.rorm.query.Path;
import com.rorm.query.Query;
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

@DisplayName("CAST PostgreSQL compatibility")
class CastFunctionCompatibilityTest {

    private static Root root;
    private static BasicAttribute flagIntAttr;

    private QueryTransformer queryTransformer;

    @BeforeAll
    static void setupMetamodel() {
        flagIntAttr = new BasicAttribute("flag_int", new AttributeLocation("items", "flag_int"), new DataType.NumericType(10, 0));
        root = new Root("items", List.of(flagIntAttr), IdDescriptor.longId("items"));
    }

    @BeforeEach
    void setUp() {
        var dslContext = DSL.using(SQLDialect.POSTGRES);
        var handlerRegistry = TestHandlerRegistry.createWithAllBuiltIns();
        var expressionTransformer = new ExpressionTransformer(handlerRegistry);
        queryTransformer = new QueryTransformer(dslContext, expressionTransformer);
    }

    @Test
    @DisplayName("CAST numeric expression to BOOLEAN uses semantic comparison instead of PostgreSQL invalid cast")
    void castNumericToBooleanUsesComparison() {
        var expr = FunctionCall.of("CAST",
            new Path(flagIntAttr, null),
            new Literal("BOOLEAN")
        );

        var query = Query.builder()
            .from(AliasedRoot.of(root))
            .selector(new SingleExprSelector(expr, false, "v"))
            .build();

        var sql = queryTransformer.transform(query).getSQL();

        assertThat(sql)
            .contains("<> 0")
            .doesNotContainIgnoringCase("cast(");
    }

    @Test
    @DisplayName("CAST to JSON produces cast(... as json)")
    void castToJson() {
        var expr = FunctionCall.of("CAST", new Path(flagIntAttr, null), new Literal("JSON"));

        var query = Query.builder()
            .from(AliasedRoot.of(root))
            .selector(new SingleExprSelector(expr, false, "v"))
            .build();

        var sql = queryTransformer.transform(query).getSQL().toLowerCase();

        assertThat(sql).contains("cast(").contains("json");
    }

    @Test
    @DisplayName("CAST to JSONB produces cast(... as jsonb)")
    void castToJsonb() {
        var expr = FunctionCall.of("CAST", new Path(flagIntAttr, null), new Literal("JSONB"));

        var query = Query.builder()
            .from(AliasedRoot.of(root))
            .selector(new SingleExprSelector(expr, false, "v"))
            .build();

        var sql = queryTransformer.transform(query).getSQL().toLowerCase();

        assertThat(sql).contains("cast(").contains("jsonb");
    }

    @Test
    @DisplayName("CAST floating literal keeps plain decimal form and avoids scientific notation")
    void castFloatLiteralAvoidsScientificNotation() {
        var expr = FunctionCall.of("CAST",
            new Literal(100.0),
            new Literal("DOUBLE")
        );

        var query = Query.builder()
            .from(AliasedRoot.of(root))
            .selector(new SingleExprSelector(expr, false, "v"))
            .build();

        var sql = queryTransformer.transform(query).getSQL();

        assertThat(sql)
            .contains("100.0")
            .doesNotContain("1E2");
    }
}



