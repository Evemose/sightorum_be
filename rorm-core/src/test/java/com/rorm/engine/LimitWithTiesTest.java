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

@DisplayName("LIMIT WITH TIES SQL generation")
class LimitWithTiesTest {

    private static Root userRoot;
    private static BasicAttribute userId;
    private static BasicAttribute userName;
    private static BasicAttribute userScore;

    private QueryTransformer queryTransformer;

    @BeforeAll
    static void setupMetamodel() {
        userId = new BasicAttribute("id", new AttributeLocation("users", "id"), new DataType.NumericType(19, 0));
        userName = new BasicAttribute("name", new AttributeLocation("users", "name"), new DataType.StringType());
        userScore = new BasicAttribute("score", new AttributeLocation("users", "score"), new DataType.NumericType(10, 2));
        userRoot = new Root("users", List.of(userId, userName, userScore), IdDescriptor.longId("users"));
    }

    @BeforeEach
    void setUp() {
        var dslContext = DSL.using(SQLDialect.POSTGRES);
        var handlerRegistry = TestHandlerRegistry.createWithAllBuiltIns();
        var expressionTransformer = new ExpressionTransformer(handlerRegistry);
        queryTransformer = new QueryTransformer(dslContext, expressionTransformer);
    }

    @Test
    @DisplayName("LIMIT WITH TIES produces FETCH FIRST ... WITH TIES")
    void limitWithTies() {
        var query = Query.builder()
            .from(AliasedRoot.of(userRoot))
            .selector(new SingleExprSelector(new Path(userName, null), false, "name"))
            .orderBy(new OrderBy(new Path(userScore, null), false, null))
            .limit(10L)
            .withTies(true)
            .build();

        var sql = queryTransformer.transform(query).getSQL();

        assertThat(sql.toLowerCase())
            .contains("fetch")
            .contains("with ties");
    }

    @Test
    @DisplayName("LIMIT without WITH TIES produces standard LIMIT")
    void limitWithoutTies() {
        var query = Query.builder()
            .from(AliasedRoot.of(userRoot))
            .selector(new SingleExprSelector(new Path(userName, null), false, "name"))
            .orderBy(new OrderBy(new Path(userScore, null), false, null))
            .limit(10L)
            .build();

        var sql = queryTransformer.transform(query).getSQL();

        // jOOQ may render as LIMIT or FETCH FIRST depending on dialect
        assertThat(sql.toLowerCase())
            .satisfiesAnyOf(
                s -> assertThat(s).contains("limit"),
                s -> assertThat(s).contains("fetch")
            );
        assertThat(sql.toLowerCase()).doesNotContain("with ties");
    }

    @Test
    @DisplayName("LIMIT WITH TIES and OFFSET work together")
    void limitWithTiesAndOffset() {
        var query = Query.builder()
            .from(AliasedRoot.of(userRoot))
            .selector(new SingleExprSelector(new Path(userName, null), false, "name"))
            .orderBy(new OrderBy(new Path(userScore, null), false, null))
            .limit(5L)
            .offset(10L)
            .withTies(true)
            .build();

        var sql = queryTransformer.transform(query).getSQL();

        assertThat(sql.toLowerCase())
            .contains("with ties")
            .contains("offset");
    }

    @Test
    @DisplayName("withTies=false behaves like normal LIMIT")
    void withTiesFalse() {
        var query = Query.builder()
            .from(AliasedRoot.of(userRoot))
            .selector(new SingleExprSelector(new Path(userName, null), false, "name"))
            .limit(10L)
            .withTies(false)
            .build();

        var sql = queryTransformer.transform(query).getSQL();

        // jOOQ may render as LIMIT or FETCH FIRST depending on dialect
        assertThat(sql.toLowerCase())
            .satisfiesAnyOf(
                s -> assertThat(s).contains("limit"),
                s -> assertThat(s).contains("fetch")
            );
        assertThat(sql.toLowerCase()).doesNotContain("with ties");
    }
}
