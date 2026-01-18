package com.rorm.engine;

import com.rorm.engine.handler.HandlerRegistry;
import com.rorm.metamodel.*;
import com.rorm.query.Expression.Aggregation;
import com.rorm.query.Expression.Literal;
import com.rorm.query.Expression.WindowFunction;
import com.rorm.query.*;
import com.rorm.query.Selector.MultiExprSelector;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Window Function Integration Tests")
class WindowFunctionTest {

    private static Root testRoot;
    private static BasicAttribute field1;
    private static BasicAttribute field2;

    private QueryTransformer queryTransformer;

    @BeforeAll
    static void setupMetamodel() {
        field1 = new BasicAttribute("field1", new AttributeLocation("test_table", "field1"), new DataType.NumericType(10, 0));
        field2 = new BasicAttribute("field2", new AttributeLocation("test_table", "field2"), new DataType.StringType());
        testRoot = new Root("test_table", List.of(field1, field2), IdDescriptor.longId("test_table"));
    }

    @BeforeEach
    void setUp() {
        var dslContext = DSL.using(SQLDialect.POSTGRES);
        var handlerRegistry = HandlerRegistry.builder().withBuiltIns().build();
        var expressionTransformer = new ExpressionTransformer(handlerRegistry);
        queryTransformer = new QueryTransformer(
            dslContext,
            expressionTransformer,
            new JoinCollector(expressionTransformer)
        );
    }

    @Test
    @DisplayName("LAG with 1 argument")
    void testLagSingleArgument() {
        var query = Query.builder()
            .from(AliasedRoot.of(testRoot))
            .selector(new MultiExprSelector(Set.of(
                new SelectedExpression(new Path(field1, null), "field1"),
                new SelectedExpression(
                    new WindowFunction(
                        "LAG",
                        List.of(new Path(field1, null)),
                        new WindowSpec(
                            null,
                            List.of(new OrderBy(new Path(field1, null), true))
                        )
                    ),
                    "prev_field1"
                )
            ), false))
            .build();

        var sql = queryTransformer.transform(query).getSQL();

        assertThat(sql)
            .containsIgnoringCase("lag(")
            .containsIgnoringCase("over")
            .containsIgnoringCase("order by");
    }

    @Test
    @DisplayName("LAG with 2 arguments (offset)")
    void testLagWithOffset() {
        var query = Query.builder()
            .from(AliasedRoot.of(testRoot))
            .selector(new MultiExprSelector(Set.of(
                new SelectedExpression(new Path(field1, null), "field1"),
                new SelectedExpression(
                    new WindowFunction(
                        "LAG",
                        List.of(new Path(field1, null), new Literal(2)),
                        new WindowSpec(
                            null,
                            List.of(new OrderBy(new Path(field1, null), true))
                        )
                    ),
                    "prev_field1"
                )
            ), false))
            .build();

        var sql = queryTransformer.transform(query).getSQL();

        assertThat(sql)
            .containsIgnoringCase("lag(")
            .contains(", 2");
    }

    @Test
    @DisplayName("LAG with 3 arguments (offset and default)")
    void testLagWithOffsetAndDefault() {
        var query = Query.builder()
            .from(AliasedRoot.of(testRoot))
            .selector(new MultiExprSelector(Set.of(
                new SelectedExpression(new Path(field1, null), "field1"),
                new SelectedExpression(
                    new WindowFunction(
                        "LAG",
                        List.of(new Path(field1, null), new Literal(2), new Literal(-1)),
                        new WindowSpec(
                            null,
                            List.of(new OrderBy(new Path(field1, null), true))
                        )
                    ),
                    "prev_field1"
                )
            ), false))
            .build();

        var sql = queryTransformer.transform(query).getSQL();

        assertThat(sql)
            .containsIgnoringCase("lag(")
            .contains(", 2, ")
            .contains("-1");
    }

    @Test
    @DisplayName("LEAD with 1 argument")
    void testLeadSingleArgument() {
        var query = Query.builder()
            .from(AliasedRoot.of(testRoot))
            .selector(new MultiExprSelector(Set.of(
                new SelectedExpression(new Path(field1, null), "field1"),
                new SelectedExpression(
                    new WindowFunction(
                        "LEAD",
                        List.of(new Path(field1, null)),
                        new WindowSpec(
                            null,
                            List.of(new OrderBy(new Path(field1, null), true))
                        )
                    ),
                    "next_field1"
                )
            ), false))
            .build();

        var sql = queryTransformer.transform(query).getSQL();

        assertThat(sql)
            .containsIgnoringCase("lead(")
            .containsIgnoringCase("over");
    }

    @Test
    @DisplayName("LEAD with 3 arguments (offset and default)")
    void testLeadWithOffsetAndDefault() {
        var query = Query.builder()
            .from(AliasedRoot.of(testRoot))
            .selector(new MultiExprSelector(Set.of(
                new SelectedExpression(new Path(field1, null), "field1"),
                new SelectedExpression(
                    new WindowFunction(
                        "LEAD",
                        List.of(new Path(field1, null), new Literal(1), new Literal(999)),
                        new WindowSpec(
                            null,
                            List.of(new OrderBy(new Path(field1, null), true))
                        )
                    ),
                    "next_field1"
                )
            ), false))
            .build();

        var sql = queryTransformer.transform(query).getSQL();

        assertThat(sql)
            .containsIgnoringCase("lead(")
            .contains(", 1, ")
            .contains("999");
    }

    @Test
    @DisplayName("FIRST_VALUE window function")
    void testFirstValue() {
        var query = Query.builder()
            .from(AliasedRoot.of(testRoot))
            .selector(new MultiExprSelector(Set.of(
                new SelectedExpression(new Path(field1, null), "field1"),
                new SelectedExpression(
                    new WindowFunction(
                        "FIRST_VALUE",
                        List.of(new Path(field1, null)),
                        new WindowSpec(
                            List.of(new Path(field2, null)),
                            List.of(new OrderBy(new Path(field1, null), false))
                        )
                    ),
                    "first_value"
                )
            ), false))
            .build();

        var sql = queryTransformer.transform(query).getSQL();

        assertThat(sql)
            .containsIgnoringCase("first_value(")
            .containsIgnoringCase("over")
            .containsIgnoringCase("partition by")
            .containsIgnoringCase("order by");
    }

    @Test
    @DisplayName("LAST_VALUE window function")
    void testLastValue() {
        var query = Query.builder()
            .from(AliasedRoot.of(testRoot))
            .selector(new MultiExprSelector(Set.of(
                new SelectedExpression(new Path(field1, null), "field1"),
                new SelectedExpression(
                    new WindowFunction(
                        "LAST_VALUE",
                        List.of(new Path(field1, null)),
                        new WindowSpec(
                            null,
                            List.of(new OrderBy(new Path(field1, null), false))
                        )
                    ),
                    "last_value"
                )
            ), false))
            .build();

        var sql = queryTransformer.transform(query).getSQL();

        assertThat(sql)
            .containsIgnoringCase("last_value(")
            .containsIgnoringCase("over");
    }

    @Test
    @DisplayName("NTH_VALUE window function")
    void testNthValue() {
        var query = Query.builder()
            .from(AliasedRoot.of(testRoot))
            .selector(new MultiExprSelector(Set.of(
                new SelectedExpression(new Path(field1, null), "field1"),
                new SelectedExpression(
                    new WindowFunction(
                        "NTH_VALUE",
                        List.of(new Path(field1, null), new Literal(3)),
                        new WindowSpec(
                            null,
                            List.of(new OrderBy(new Path(field1, null), false))
                        )
                    ),
                    "third_value"
                )
            ), false))
            .build();

        var sql = queryTransformer.transform(query).getSQL();

        assertThat(sql)
            .containsIgnoringCase("nth_value(")
            .contains(", ?")
            .containsIgnoringCase("over");
    }

    @Test
    @DisplayName("NTILE window function")
    void testNtile() {
        var query = Query.builder()
            .from(AliasedRoot.of(testRoot))
            .selector(new MultiExprSelector(Set.of(
                new SelectedExpression(new Path(field1, null), "field1"),
                new SelectedExpression(
                    new WindowFunction(
                        "NTILE",
                        List.of(new Literal(4)),
                        new WindowSpec(
                            null,
                            List.of(new OrderBy(new Path(field1, null), false))
                        )
                    ),
                    "quartile"
                )
            ), false))
            .build();

        var sql = queryTransformer.transform(query).getSQL();

        assertThat(sql)
            .containsIgnoringCase("ntile(")
            .contains("4")
            .containsIgnoringCase("over");
    }

    @Test
    @DisplayName("PERCENT_RANK window function")
    void testPercentRank() {
        var query = Query.builder()
            .from(AliasedRoot.of(testRoot))
            .selector(new MultiExprSelector(Set.of(
                new SelectedExpression(new Path(field1, null), "field1"),
                new SelectedExpression(
                    new WindowFunction(
                        "PERCENT_RANK",
                        List.of(),
                        new WindowSpec(
                            List.of(new Path(field2, null)),
                            List.of(new OrderBy(new Path(field1, null), false))
                        )
                    ),
                    "percent_rank"
                )
            ), false))
            .build();

        var sql = queryTransformer.transform(query).getSQL();

        assertThat(sql)
            .containsIgnoringCase("percent_rank(")
            .containsIgnoringCase("over")
            .containsIgnoringCase("partition by");
    }

    @Test
    @DisplayName("CUME_DIST window function")
    void testCumeDist() {
        var query = Query.builder()
            .from(AliasedRoot.of(testRoot))
            .selector(new MultiExprSelector(Set.of(
                new SelectedExpression(new Path(field1, null), "field1"),
                new SelectedExpression(
                    new WindowFunction(
                        "CUME_DIST",
                        List.of(),
                        new WindowSpec(
                            null,
                            List.of(new OrderBy(new Path(field1, null), false))
                        )
                    ),
                    "cume_dist"
                )
            ), false))
            .build();

        var sql = queryTransformer.transform(query).getSQL();

        assertThat(sql)
            .containsIgnoringCase("cume_dist(")
            .containsIgnoringCase("over");
    }

    @Test
    @DisplayName("Multiple window functions in one query")
    void testMultipleWindowFunctions() {
        var query = Query.builder()
            .from(AliasedRoot.of(testRoot))
            .selector(new MultiExprSelector(Set.of(
                new SelectedExpression(new Path(field1, null), "field1"),
                new SelectedExpression(
                    new WindowFunction(
                        "ROW_NUMBER",
                        List.of(),
                        new WindowSpec(
                            List.of(new Path(field2, null)),
                            List.of(new OrderBy(new Path(field1, null), false))
                        )
                    ),
                    "row_num"
                ),
                new SelectedExpression(
                    new WindowFunction(
                        "LAG",
                        List.of(new Path(field1, null), new Literal(1)),
                        new WindowSpec(
                            List.of(new Path(field2, null)),
                            List.of(new OrderBy(new Path(field1, null), false))
                        )
                    ),
                    "prev_value"
                ),
                new SelectedExpression(
                    new WindowFunction(
                        "LEAD",
                        List.of(new Path(field1, null), new Literal(1)),
                        new WindowSpec(
                            List.of(new Path(field2, null)),
                            List.of(new OrderBy(new Path(field1, null), false))
                        )
                    ),
                    "next_value"
                )
            ), false))
            .build();

        var sql = queryTransformer.transform(query).getSQL();

        assertThat(sql)
            .containsIgnoringCase("row_number()")
            .containsIgnoringCase("lag(")
            .containsIgnoringCase("lead(");
    }

    @Test
    @DisplayName("Aggregation with DISTINCT flag - COUNT DISTINCT")
    void testCountDistinct() {
        var query = Query.builder()
            .from(AliasedRoot.of(testRoot))
            .selector(new MultiExprSelector(Set.of(
                new SelectedExpression(new Path(field1, null), "field1"),
                new SelectedExpression(
                    new Aggregation("COUNT", List.of(new Path(field2, null)), true),
                    "distinct_count"
                )
            ), false))
            .groupBy(new GroupBy(new Path(field1, null)))
            .build();

        var sql = queryTransformer.transform(query).getSQL();

        assertThat(sql)
            .containsIgnoringCase("count(distinct")
            .containsIgnoringCase("group by");
    }

    @Test
    @DisplayName("Aggregation with DISTINCT flag - SUM DISTINCT")
    void testSumDistinct() {
        var query = Query.builder()
            .from(AliasedRoot.of(testRoot))
            .selector(new MultiExprSelector(Set.of(
                new SelectedExpression(new Path(field1, null), "field1"),
                new SelectedExpression(
                    new Aggregation("SUM", List.of(new Path(field2, null)), true),
                    "distinct_sum"
                )
            ), false))
            .groupBy(new GroupBy(new Path(field1, null)))
            .build();

        var sql = queryTransformer.transform(query).getSQL();

        assertThat(sql).containsIgnoringCase("sum(distinct");
    }

    @Test
    @DisplayName("Aggregation with DISTINCT flag - AVG DISTINCT")
    void testAvgDistinct() {
        var query = Query.builder()
            .from(AliasedRoot.of(testRoot))
            .selector(new MultiExprSelector(Set.of(
                new SelectedExpression(new Path(field1, null), "field1"),
                new SelectedExpression(
                    new Aggregation("AVG", List.of(new Path(field2, null)), true),
                    "distinct_avg"
                )
            ), false))
            .groupBy(new GroupBy(new Path(field1, null)))
            .build();

        var sql = queryTransformer.transform(query).getSQL();

        assertThat(sql).containsIgnoringCase("avg(distinct");
    }

    @Test
    @DisplayName("Regular aggregation without DISTINCT flag")
    void testRegularAggregation() {
        var query = Query.builder()
            .from(AliasedRoot.of(testRoot))
            .selector(new MultiExprSelector(Set.of(
                new SelectedExpression(new Path(field1, null), "field1"),
                new SelectedExpression(
                    new Aggregation("COUNT", List.of(new Path(field2, null)), false),
                    "total_count"
                ),
                new SelectedExpression(
                    new Aggregation("SUM", List.of(new Path(field2, null)), false),
                    "total_sum"
                )
            ), false))
            .groupBy(new GroupBy(new Path(field1, null)))
            .build();

        var sql = queryTransformer.transform(query).getSQL();

        assertThat(sql)
            .containsIgnoringCase("count(")
            .containsIgnoringCase("sum(");
        // Should NOT contain DISTINCT
        assertThat(sql.toLowerCase())
            .doesNotContain("count(distinct")
            .doesNotContain("sum(distinct");
    }
}

