package com.rorm.engine;

import com.rorm.metamodel.AttributeLocation;
import com.rorm.metamodel.BasicAttribute;
import com.rorm.metamodel.ReferenceAttribute.JoinTableMapping;
import com.rorm.metamodel.Root;
import com.rorm.metamodel.SingularReferenceAttribute;
import com.rorm.query.Expression.BinaryExpression;
import com.rorm.query.Expression.Literal;
import com.rorm.query.Expression.TernaryExpression;
import com.rorm.query.Expression.UnaryExpression;
import com.rorm.query.Operator.BinaryOperator;
import com.rorm.query.Operator.UnaryOperator;
import com.rorm.query.OrderBy;
import com.rorm.query.Path;
import com.rorm.query.Query;
import com.rorm.query.SelectedExpression;
import com.rorm.query.Selector.MultiExprSelector;
import com.rorm.query.Selector.RootSelector;
import com.rorm.query.Selector.SingleExprSelector;
import com.rorm.testutil.AbstractPostgresTest;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static java.lang.IO.println;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

@Testcontainers
class QueryTransformerTest extends AbstractPostgresTest {

    private static BasicAttribute userId;
    private static BasicAttribute userName;
    private static BasicAttribute userEmail;
    private static BasicAttribute profileBio;
    private static BasicAttribute addressCity;
    private static SingularReferenceAttribute userProfile;
    private static SingularReferenceAttribute profileAddress;
    private static Root userRoot;

    private DSLContext dsl;
    private QueryTransformer transformer;

    @BeforeAll
    static void setupMetamodel() {
        // Address (no dependencies)
        addressCity = new BasicAttribute("city", new AttributeLocation("addresses", "city"));
        var addressRoot = new Root("addresses", List.of(addressCity));

        // Profile (depends on Address)
        profileBio = new BasicAttribute("bio", new AttributeLocation("profiles", "bio"));
        profileAddress = new SingularReferenceAttribute("address", addressRoot,
            new JoinTableMapping(new AttributeLocation("profiles", "address_id"), "id"));
        var profileRoot = new Root("profiles", List.of(profileBio, profileAddress));

        // User (depends on Profile)
        userId = new BasicAttribute("id", new AttributeLocation("users", "id"));
        userName = new BasicAttribute("name", new AttributeLocation("users", "name"));
        userEmail = new BasicAttribute("email", new AttributeLocation("users", "email"));
        userProfile = new SingularReferenceAttribute("profile", profileRoot,
            new JoinTableMapping(new AttributeLocation("users", "profile_id"), "id"));
        userRoot = new Root("users", List.of(userId, userName, userEmail, userProfile));
    }

    static Stream<Arguments> queryTestCases() {
        return Stream.of(
            simpleSelectAll(),
            selectWithWhereEquals(),
            selectWithWhereLike(),
            selectWithWhereIsNull(),
            selectWithManyToOneJoin(),
            selectWithManyToOneDoubleJoin(),
            selectWithWhereAndJoin(),
            selectDistinct(),
            selectSingleExpression(),
            selectMultipleExpressions(),
            selectWithBinaryExpressionAnd(),
            selectWithBinaryExpressionOr(),
            selectWithBetween(),
            selectWithOrderByAsc(),
            selectWithOrderByDesc(),
            selectWithLimit(),
            selectWithOffset(),
            selectWithLimitAndOffset()
        );
    }

    private static Arguments simpleSelectAll() {
        var query = Query.builder()
            .from(userRoot)
            .selector(new RootSelector(userRoot, false))
            .build();
        return Arguments.of("Simple SELECT ALL", query, "select", (Consumer<Result<Record>>) result -> {
            assertThat(result)
                .hasSize(2)
                .extracting(r -> r.get("name"), r -> r.get("email"))
                .containsExactlyInAnyOrder(
                    tuple("Alice", "alice@test.com"),
                    tuple("Bob", "bob@test.com")
                );
        });
    }

    private static Arguments selectWithWhereEquals() {
        var namePath = new Path(userName, null);
        var query = Query.builder()
            .from(userRoot)
            .selector(new RootSelector(userRoot, false))
            .where(new BinaryExpression(namePath, BinaryOperator.EQUALS, new Literal("Alice")))
            .build();

        return Arguments.of("SELECT with WHERE equals", query, "where", (Consumer<Result<Record>>) result -> {
            assertThat(result)
                .hasSize(1)
                .extracting(r -> r.get("name"), r -> r.get("email"))
                .containsExactly(tuple("Alice", "alice@test.com"));
        });
    }

    private static Arguments selectWithWhereLike() {
        var emailPath = new Path(userEmail, null);
        var query = Query.builder()
            .from(userRoot)
            .selector(new RootSelector(userRoot, false))
            .where(new BinaryExpression(emailPath, BinaryOperator.LIKE, new Literal("%test.com")))
            .build();

        return Arguments.of("SELECT with WHERE LIKE", query, "like", (Consumer<Result<Record>>) result -> {
            assertThat(result)
                .hasSize(2)
                .extracting(r -> r.get("email"))
                .containsExactlyInAnyOrder("alice@test.com", "bob@test.com");
        });
    }

    private static Arguments selectWithWhereIsNull() {
        var emailPath = new Path(userEmail, null);
        var query = Query.builder()
            .from(userRoot)
            .selector(new RootSelector(userRoot, false))
            .where(new UnaryExpression(UnaryOperator.IS_NOT_NULL, emailPath))
            .build();

        return Arguments.of("SELECT with WHERE IS NOT NULL", query, "is not null", (Consumer<Result<Record>>) result -> {
            assertThat(result)
                .hasSize(2)
                .extracting(r -> r.get("email"))
                .allMatch(Objects::nonNull)
                .containsExactlyInAnyOrder("alice@test.com", "bob@test.com");
        });
    }

    private static Arguments selectWithManyToOneJoin() {
        var bioPath = new Path(profileBio, new Path(userProfile, null));
        var query = Query.builder()
            .from(userRoot)
            .selector(new SingleExprSelector(bioPath, false, null))
            .build();

        return Arguments.of("SELECT with many-to-one join (user->profile)", query, "left outer join", (Consumer<Result<Record>>) result -> {
            assertThat(result)
                .hasSize(2)
                .extracting(r -> r.get("bio"))
                .containsExactlyInAnyOrder("Software Engineer", "Designer");
        });
    }

    private static Arguments selectWithManyToOneDoubleJoin() {
        var cityPath = new Path(addressCity, new Path(profileAddress, new Path(userProfile, null)));

        var query = Query.builder()
            .from(userRoot)
            .selector(new SingleExprSelector(cityPath, false, null))
            .build();

        return Arguments.of("SELECT with double many-to-one join (user->profile->address)", query, "left outer join", (Consumer<Result<Record>>) result -> {
            assertThat(result)
                .hasSize(2)
                .extracting(r -> r.get("city"))
                .containsExactlyInAnyOrder("New York", "London");
        });
    }

    private static Arguments selectWithWhereAndJoin() {
        var bioPath = new Path(profileBio, new Path(userProfile, null));
        var query = Query.builder()
            .from(userRoot)
            .selector(new RootSelector(userRoot, false))
            .where(new BinaryExpression(bioPath, BinaryOperator.EQUALS, new Literal("Software Engineer")))
            .build();

        return Arguments.of("SELECT with WHERE and JOIN", query, "left outer join", (Consumer<Result<Record>>) result -> {
            assertThat(result)
                .hasSize(1)
                .extracting(r -> r.get("name"))
                .containsExactly("Alice");
        });
    }

    private static Arguments selectDistinct() {
        var query = Query.builder()
            .from(userRoot)
            .selector(new RootSelector(userRoot, true))
            .build();
        return Arguments.of("SELECT DISTINCT", query, "select distinct", (Consumer<Result<Record>>) result -> {
            assertThat(result)
                .hasSize(2)
                .extracting(r -> r.get("name"))
                .containsExactlyInAnyOrder("Alice", "Bob");
        });
    }

    private static Arguments selectSingleExpression() {
        var namePath = new Path(userName, null);
        var query = Query.builder()
            .from(userRoot)
            .selector(new SingleExprSelector(namePath, false, null))
            .build();

        return Arguments.of("SELECT single expression", query, "select", (Consumer<Result<Record>>) result -> {
            assertThat(result)
                .hasSize(2)
                .extracting(r -> r.get("name"))
                .containsExactlyInAnyOrder("Alice", "Bob");
        });
    }

    private static Arguments selectMultipleExpressions() {
        var namePath = new Path(userName, null);
        var emailPath = new Path(userEmail, null);

        var query = Query.builder()
            .from(userRoot)
            .selector(new MultiExprSelector(Set.of(
                new SelectedExpression(namePath, null),
                new SelectedExpression(emailPath, null)
            ), false))
            .build();
        return Arguments.of("SELECT multiple expressions", query, "select", (Consumer<Result<Record>>) result ->
            assertThat(result)
                .hasSize(2)
                .extracting(r -> r.get("name"), r -> r.get("email"))
                .containsExactlyInAnyOrder(
                    tuple("Alice", "alice@test.com"),
                    tuple("Bob", "bob@test.com")
                )
        );
    }

    private static Arguments selectWithBinaryExpressionAnd() {
        var namePath = new Path(userName, null);
        var emailPath = new Path(userEmail, null);

        var namePredicate = new BinaryExpression(namePath, BinaryOperator.EQUALS, new Literal("Alice"));
        var emailPredicate = new BinaryExpression(emailPath, BinaryOperator.LIKE, new Literal("%test.com"));

        var query = Query.builder()
            .from(userRoot)
            .selector(new RootSelector(userRoot, false))
            .where(new BinaryExpression(namePredicate, BinaryOperator.AND, emailPredicate))
            .build();

        return Arguments.of("SELECT with composite AND predicate", query, "and", (Consumer<Result<Record>>) result ->
            assertThat(result)
                .hasSize(1)
                .extracting(r -> r.get("name"))
                .containsExactly("Alice")
        );
    }

    private static Arguments selectWithBinaryExpressionOr() {
        var namePath = new Path(userName, null);

        var alicePredicate = new BinaryExpression(namePath, BinaryOperator.EQUALS, new Literal("Alice"));
        var bobPredicate = new BinaryExpression(namePath, BinaryOperator.EQUALS, new Literal("Bob"));

        var query = Query.builder()
            .from(userRoot)
            .selector(new RootSelector(userRoot, false))
            .where(new BinaryExpression(alicePredicate, BinaryOperator.OR, bobPredicate))
            .build();

        return Arguments.of("SELECT with composite OR predicate", query, "or", (Consumer<Result<Record>>) result ->
            assertThat(result)
                .hasSize(2)
                .extracting(r -> r.get("name"))
                .containsExactlyInAnyOrder("Alice", "Bob")
        );
    }

    private static Arguments selectWithBetween() {
        var idPath = new Path(userId, null);
        var query = Query.builder()
            .from(userRoot)
            .selector(new RootSelector(userRoot, false))
            .where(new TernaryExpression(idPath, com.rorm.query.Operator.TernaryOperator.BETWEEN,
                new Literal(1L), new Literal(2L)))
            .build();

        return Arguments.of("SELECT with BETWEEN", query, "between", (Consumer<Result<Record>>) result -> {
            assertThat(result)
                .hasSize(2)
                .extracting(r -> r.get("name"))
                .containsExactlyInAnyOrder("Alice", "Bob");
        });
    }

    private static Arguments selectWithOrderByAsc() {
        var namePath = new Path(userName, null);
        var query = Query.builder()
            .from(userRoot)
            .selector(new RootSelector(userRoot, false))
            .orderBy(new OrderBy(namePath, true))
            .build();

        return Arguments.of("SELECT with ORDER BY ASC", query, "order by", (Consumer<Result<Record>>) result -> {
            assertThat(result)
                .hasSize(2)
                .extracting(r -> r.get("name"))
                .containsExactly("Alice", "Bob");
        });
    }

    private static Arguments selectWithOrderByDesc() {
        var namePath = new Path(userName, null);
        var query = Query.builder()
            .from(userRoot)
            .selector(new RootSelector(userRoot, false))
            .orderBy(new OrderBy(namePath, false))
            .build();

        return Arguments.of("SELECT with ORDER BY DESC", query, "desc", (Consumer<Result<Record>>) result -> {
            assertThat(result)
                .hasSize(2)
                .extracting(r -> r.get("name"))
                .containsExactly("Bob", "Alice");
        });
    }

    private static Arguments selectWithLimit() {
        var query = Query.builder()
            .from(userRoot)
            .selector(new RootSelector(userRoot, false))
            .limit(1L)
            .build();
        return Arguments.of("SELECT with LIMIT", query, "", (Consumer<Result<Record>>) result -> {
            assertThat(result)
                .hasSize(1)
                .extracting(r -> r.get("name"))
                .containsAnyOf("Alice", "Bob");
        });
    }

    private static Arguments selectWithOffset() {
        var query = Query.builder()
            .from(userRoot)
            .selector(new RootSelector(userRoot, false))
            .offset(1L)
            .build();
        return Arguments.of("SELECT with OFFSET", query, "offset", (Consumer<Result<Record>>) result -> {
            assertThat(result)
                .hasSize(1)
                .extracting(r -> r.get("name"))
                .containsAnyOf("Alice", "Bob");
        });
    }

    private static Arguments selectWithLimitAndOffset() {
        var query = Query.builder()
            .from(userRoot)
            .selector(new RootSelector(userRoot, false))
            .limit(1L)
            .offset(1L)
            .build();
        return Arguments.of("SELECT with LIMIT and OFFSET", query, "", (Consumer<Result<Record>>) result -> {
            assertThat(result)
                .hasSize(1)
                .extracting(r -> r.get("name"))
                .containsAnyOf("Alice", "Bob");
        });
    }

    @BeforeEach
    @SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
    void setUp() {
        var schemaName = "test_" + UUID.randomUUID().toString().replace("-", "_");

        dsl = DSL.using(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        var expressionTransformer = new ExpressionTransformer();
        transformer = new QueryTransformer(dsl, expressionTransformer, new JoinCollector(expressionTransformer));

        dsl.execute("create schema " + schemaName);
        dsl.execute("set search_path to " + schemaName);

        dsl.execute("drop table if exists order_items cascade");
        dsl.execute("drop table if exists orders cascade");
        dsl.execute("drop table if exists addresses cascade");
        dsl.execute("drop table if exists profiles cascade");
        dsl.execute("drop table if exists users cascade");

        dsl.execute("""
                create table addresses (
                    id bigserial primary key,
                    city varchar(255),
                    street varchar(255)
                )
            """);

        dsl.execute("""
                create table profiles (
                    id bigserial primary key,
                    bio text,
                    address_id bigint references addresses(id)
                )
            """);

        dsl.execute("""
                create table users (
                    id bigserial primary key,
                    name varchar(255),
                    email varchar(255),
                    profile_id bigint references profiles(id)
                )
            """);

        dsl.execute("""
                create table orders (
                    id bigserial primary key,
                    user_id bigint references users(id),
                    total decimal(10,2)
                )
            """);

        dsl.execute("""
                create table order_items (
                    id bigserial primary key,
                    order_id bigint references orders(id),
                    product_name varchar(255),
                    quantity int
                )
            """);

        dsl.execute("insert into addresses (city, street) values ('New York', '5th Ave'), ('London', 'Baker St')");
        dsl.execute("insert into profiles (bio, address_id) values ('Software Engineer', 1), ('Designer', 2)");
        dsl.execute("insert into users (name, email, profile_id) values ('Alice', 'alice@test.com', 1), ('Bob', 'bob@test.com', 2)");
        dsl.execute("insert into orders (user_id, total) values (1, 100.00), (1, 200.00), (2, 150.00)");
        dsl.execute("insert into order_items (order_id, product_name, quantity) values (1, 'Book', 2), (1, 'Pen', 5), (2, 'Laptop', 1)");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("queryTestCases")
    @SuppressWarnings("unchecked")
    void testQueryTransformation(String testName, Query query, String expectedSqlFragment, Consumer<Result<Record>> resultValidator) {
        var sql = transformer.transform(query);
        var sqlString = sql.getSQL();

        if (!expectedSqlFragment.isEmpty()) {
            assertThat(sqlString.toLowerCase()).as("SQL for " + testName)
                .contains(expectedSqlFragment.toLowerCase());
        }

        println("Generated SQL for " + testName + ": " + sqlString + "\n");

        var result = (Result<Record>) dsl.fetch(sql);
        resultValidator.accept(result);
    }
}
