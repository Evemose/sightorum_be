package com.rorm.engine;

import com.rorm.metamodel.*;
import com.rorm.query.*;
import com.rorm.query.Expression.FunctionCall;
import com.rorm.query.Expression.Literal;
import com.rorm.query.Join.JoinType;
import com.rorm.query.Selector.MultiExprSelector;
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

@DisplayName("COALESCE function SQL generation")
class CoalesceFunctionTest {

    private static Root mainRoot;
    private static Root joinedRoot;
    private static BasicAttribute mainId;
    private static BasicAttribute mainName;
    private static BasicAttribute joinedId;
    private static BasicAttribute joinedFlag;

    private QueryTransformer queryTransformer;

    @BeforeAll
    static void setupMetamodel() {
        joinedId = new BasicAttribute("id", new AttributeLocation("joined_table", "id"), new DataType.NumericType(19, 0));
        joinedFlag = new BasicAttribute("has_flag", new AttributeLocation("joined_table", "has_flag"), new DataType.BooleanType());
        joinedRoot = new Root("joined_table", List.of(joinedId, joinedFlag), IdDescriptor.longId("joined_table"));

        mainId = new BasicAttribute("id", new AttributeLocation("main_table", "id"), new DataType.NumericType(19, 0));
        mainName = new BasicAttribute("name", new AttributeLocation("main_table", "name"), new DataType.StringType());
        mainRoot = new Root("main_table", List.of(mainId, mainName), IdDescriptor.longId("main_table"));
    }

    @BeforeEach
    void setUp() {
        var dslContext = DSL.using(SQLDialect.POSTGRES);
        var handlerRegistry = TestHandlerRegistry.createWithAllBuiltIns();
        var expressionTransformer = new ExpressionTransformer(handlerRegistry);
        queryTransformer = new QueryTransformer(
            dslContext,
            expressionTransformer,
            new JoinCollector(expressionTransformer)
        );
    }

    @Test
    @DisplayName("COALESCE with joined path and boolean literal produces valid SQL")
    void coalesceWithJoinedPathAndBooleanLiteral() {
        var aliasedJoined = AliasedRoot.of(joinedRoot, "j");
        var joinedFlagPath = new Path(joinedFlag, new Path(aliasedJoined, null));
        var mainIdPath = new Path(mainId, null);
        var joinedIdPath = new Path(joinedId, new Path(aliasedJoined, null));
        var joinCondition = Expression.BinaryExpression.eq(mainIdPath, joinedIdPath);

        var coalesceExpr = new FunctionCall("COALESCE", List.of(
            joinedFlagPath,
            new Literal(false)
        ));

        var query = Query.builder()
            .from(AliasedRoot.of(mainRoot))
            .joins(new LinkedHashSet<>(List.of(new Join(aliasedJoined, JoinType.LEFT, joinCondition))))
            .selector(new MultiExprSelector(Set.of(
                new SelectedExpression(new Path(mainName, null), "name"),
                new SelectedExpression(coalesceExpr, "has_flag")
            ), false))
            .build();

        var sql = queryTransformer.transform(query).getSQL();

        assertThat(sql)
            .containsIgnoringCase("coalesce(")
            .doesNotContain("any[]")
            .doesNotContain("any[");
    }

    @Test
    @DisplayName("COALESCE with two paths produces valid SQL")
    void coalesceWithTwoPaths() {
        var coalesceExpr = new FunctionCall("COALESCE", List.of(
            new Path(mainName, null),
            new Literal("unknown")
        ));

        var query = Query.builder()
            .from(AliasedRoot.of(mainRoot))
            .selector(new MultiExprSelector(Set.of(
                new SelectedExpression(coalesceExpr, "name_or_default")
            ), false))
            .build();

        var sql = queryTransformer.transform(query).getSQL();

        assertThat(sql)
            .containsIgnoringCase("coalesce(")
            .doesNotContain("any[]");
    }
}
