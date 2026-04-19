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

@DisplayName("String functions (SPLIT_PART, REGEXP_REPLACE) SQL generation")
class StringFunctionTest {

    private static Root root;
    private static BasicAttribute nameAttr;

    private QueryTransformer queryTransformer;

    @BeforeAll
    static void setupMetamodel() {
        nameAttr = new BasicAttribute("name", new AttributeLocation("items", "name"), new DataType.StringType());
        root = new Root("items", List.of(nameAttr), IdDescriptor.longId("items"));
    }

    @BeforeEach
    void setUp() {
        var dslContext = DSL.using(SQLDialect.POSTGRES);
        var handlerRegistry = TestHandlerRegistry.createWithAllBuiltIns();
        var expressionTransformer = new ExpressionTransformer(handlerRegistry);
        queryTransformer = new QueryTransformer(dslContext, expressionTransformer);
    }

    @Test
    @DisplayName("SPLIT_PART splits a string on delimiter")
    void splitPart() {
        var expr = FunctionCall.of(StandardFunction.SPLIT_PART,
            new Path(nameAttr, null),
            new Literal("."),
            new Literal(2)
        );

        var query = Query.builder()
            .from(AliasedRoot.of(root))
            .selector(new SingleExprSelector(expr, false, "part"))
            .build();

        var sql = queryTransformer.transform(query).getSQL();

        assertThat(sql).containsIgnoringCase("split_part");
    }

    @Test
    @DisplayName("SPLIT_PART with literal string works")
    void splitPartLiteral() {
        var expr = FunctionCall.of(StandardFunction.SPLIT_PART,
            new Literal("a.b.c"),
            new Literal("."),
            new Literal(1)
        );

        var query = Query.builder()
            .from(AliasedRoot.of(root))
            .selector(new SingleExprSelector(expr, false, "part"))
            .build();

        var sql = queryTransformer.transform(query).getSQL();

        assertThat(sql).containsIgnoringCase("split_part");
    }

    @Test
    @DisplayName("REGEXP_REPLACE replaces pattern in string")
    void regexpReplace() {
        var expr = FunctionCall.of(StandardFunction.REGEXP_REPLACE,
            new Path(nameAttr, null),
            new Literal("[0-9]+"),
            new Literal("NUM")
        );

        var query = Query.builder()
            .from(AliasedRoot.of(root))
            .selector(new SingleExprSelector(expr, false, "cleaned"))
            .build();

        var sql = queryTransformer.transform(query).getSQL();

        assertThat(sql).containsIgnoringCase("regexp_replace");
    }

    @Test
    @DisplayName("REGEXP_REPLACE with flags falls back to generic function")
    void regexpReplaceWithFlags() {
        var expr = FunctionCall.of(StandardFunction.REGEXP_REPLACE,
            new Path(nameAttr, null),
            new Literal("[a-z]"),
            new Literal("X"),
            new Literal("gi")
        );

        var query = Query.builder()
            .from(AliasedRoot.of(root))
            .selector(new SingleExprSelector(expr, false, "replaced"))
            .build();

        var sql = queryTransformer.transform(query).getSQL();

        assertThat(sql).containsIgnoringCase("regexp_replace");
    }

    @Test
    @DisplayName("SPLIT_PART can be used in WHERE clause")
    void splitPartInWhere() {
        var expr = FunctionCall.of(StandardFunction.SPLIT_PART,
            new Path(nameAttr, null),
            new Literal("@"),
            new Literal(2)
        );

        var query = Query.builder()
            .from(AliasedRoot.of(root))
            .selector(new SingleExprSelector(new Path(nameAttr, null), false, "name"))
            .where(Expression.BinaryExpression.eq(expr, new Literal("example.com")))
            .build();

        var sql = queryTransformer.transform(query).getSQL();

        assertThat(sql)
            .containsIgnoringCase("split_part")
            .containsIgnoringCase("where");
    }

    @Test
    @DisplayName("POSITION uses PostgreSQL IN syntax")
    void positionUsesInSyntax() {
        var expr = FunctionCall.of(StandardFunction.POSITION,
            new Literal("a"),
            new Path(nameAttr, null)
        );

        var query = Query.builder()
            .from(AliasedRoot.of(root))
            .selector(new SingleExprSelector(expr, false, "pos"))
            .build();

        var sql = queryTransformer.transform(query).getSQL();

        assertThat(sql)
            .containsIgnoringCase("position(")
            .containsIgnoringCase(" in ");
    }

    @Test
    @DisplayName("REPEAT casts count to integer")
    void repeatCastsCountToInteger() {
        var expr = FunctionCall.of(StandardFunction.REPEAT,
            new Literal("*"),
            new Literal(3)
        );

        var query = Query.builder()
            .from(AliasedRoot.of(root))
            .selector(new SingleExprSelector(expr, false, "stars"))
            .build();

        var sql = queryTransformer.transform(query).getSQL();

        assertThat(sql)
            .containsIgnoringCase("repeat(")
            .containsIgnoringCase("cast(")
            .containsIgnoringCase(" as integer)");
    }

    @Test
    @DisplayName("LTRIM generates ltrim SQL function")
    void ltrimGeneratesSql() {
        var expr = FunctionCall.of(StandardFunction.LTRIM,
            new Literal("   abc")
        );

        var query = Query.builder()
            .from(AliasedRoot.of(root))
            .selector(new SingleExprSelector(expr, false, "v"))
            .build();

        var sql = queryTransformer.transform(query).getSQL();

        assertThat(sql).containsIgnoringCase("ltrim(");
    }

    @Test
    @DisplayName("RTRIM generates rtrim SQL function")
    void rtrimGeneratesSql() {
        var expr = FunctionCall.of(StandardFunction.RTRIM,
            new Literal("abc   ")
        );

        var query = Query.builder()
            .from(AliasedRoot.of(root))
            .selector(new SingleExprSelector(expr, false, "v"))
            .build();

        var sql = queryTransformer.transform(query).getSQL();

        assertThat(sql).containsIgnoringCase("rtrim(");
    }
}
