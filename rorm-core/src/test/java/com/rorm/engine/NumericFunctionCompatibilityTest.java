package com.rorm.engine;

import com.rorm.metamodel.*;
import com.rorm.query.Expression.FunctionCall;
import com.rorm.query.Expression.Literal;
import com.rorm.query.Path;
import com.rorm.query.Query;
import com.rorm.query.Selector.SingleExprSelector;
import com.rorm.query.StandardFunction;
import com.rorm.testutil.TestHandlerRegistry;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Numeric function PostgreSQL compatibility")
class NumericFunctionCompatibilityTest {

    private static Root root;
    private static BasicAttribute amountAttr;

    private QueryTransformer queryTransformer;

    @BeforeAll
    static void setupMetamodel() {
        amountAttr = new BasicAttribute("amount", new AttributeLocation("items", "amount"), new DataType.NumericType(10, 2));
        root = new Root("items", List.of(amountAttr), IdDescriptor.longId("items"));
    }

    @BeforeEach
    void setUp() {
        var dslContext = DSL.using(SQLDialect.POSTGRES);
        var handlerRegistry = TestHandlerRegistry.createWithAllBuiltIns();
        var expressionTransformer = new ExpressionTransformer(handlerRegistry);
        queryTransformer = new QueryTransformer(dslContext, expressionTransformer);
    }

    @Test
    @DisplayName("LOG with two arguments normalizes both args to numeric")
    void logTwoArgsCastsToNumeric() {
        var expr = FunctionCall.of(StandardFunction.LOG,
            new Literal(10.0),
            new Path(amountAttr, null)
        );

        var query = Query.builder()
            .from(AliasedRoot.of(root))
            .selector(new SingleExprSelector(expr, false, "v"))
            .build();

        var sql = queryTransformer.transform(query).getSQL();

        assertThat(sql)
            .containsIgnoringCase("log(")
            .containsIgnoringCase("cast(")
            .containsIgnoringCase("as numeric)");
    }

    @Test
    @DisplayName("ROUND with precision casts precision argument to integer")
    void roundTwoArgsCastsPrecisionToInteger() {
        var expr = FunctionCall.of(StandardFunction.ROUND,
            new Path(amountAttr, null),
            new Literal(2)
        );

        var query = Query.builder()
            .from(AliasedRoot.of(root))
            .selector(new SingleExprSelector(expr, false, "v"))
            .build();

        var sql = queryTransformer.transform(query).getSQL();

        assertThat(sql)
            .containsIgnoringCase("round(")
            .containsIgnoringCase("cast(")
            .containsIgnoringCase("as integer)");
    }
}


