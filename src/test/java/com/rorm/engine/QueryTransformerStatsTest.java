package com.rorm.engine;

import com.rorm.metamodel.AttributeLocation;
import com.rorm.metamodel.BasicAttribute;
import com.rorm.metamodel.ReferenceAttribute.JoinTableMapping;
import com.rorm.metamodel.Root;
import com.rorm.metamodel.SingularReferenceAttribute;
import com.rorm.query.Expression.BinaryExpression;
import com.rorm.query.Expression.FunctionCall;
import com.rorm.query.Expression.Literal;
import com.rorm.query.Expression.WindowFunction;
import com.rorm.query.*;
import com.rorm.query.Operator.BinaryOperator;
import com.rorm.query.Selector.MultiExprSelector;
import com.rorm.query.Selector.SingleExprSelector;
import com.rorm.testutil.AbstractPostgresTest;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static java.lang.IO.println;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

@Testcontainers
@SuppressWarnings("FieldCanBeLocal")
class QueryTransformerStatsTest extends AbstractPostgresTest {

    private static BasicAttribute userId;
    private static BasicAttribute userName;
    private static BasicAttribute userEmail;
    private static BasicAttribute profileId;
    private static BasicAttribute profileBio;
    private static BasicAttribute addressId;
    private static BasicAttribute addressCity;
    private static BasicAttribute addressStreet;
    private static BasicAttribute orderId;
    private static BasicAttribute orderTotal;
    private static BasicAttribute orderItemId;
    private static BasicAttribute orderItemProductName;
    private static BasicAttribute orderItemQuantity;

    private static SingularReferenceAttribute userProfile;
    private static SingularReferenceAttribute profileAddress;
    private static SingularReferenceAttribute orderUser;
    private static SingularReferenceAttribute orderItemOrder;

    private static Root userRoot;
    private static Root orderRoot;
    private static Root orderItemRoot;

    private QueryTransformer transformer;

    @BeforeAll
    static void setupMetamodel() {
        // Address (no dependencies)
        addressId = new BasicAttribute("id", new AttributeLocation("addresses", "id"));
        addressCity = new BasicAttribute("city", new AttributeLocation("addresses", "city"));
        addressStreet = new BasicAttribute("street", new AttributeLocation("addresses", "street"));
        var addressRoot = new Root("addresses", List.of(addressId, addressCity, addressStreet));

        // Profile (depends on Address)
        profileId = new BasicAttribute("id", new AttributeLocation("profiles", "id"));
        profileBio = new BasicAttribute("bio", new AttributeLocation("profiles", "bio"));
        profileAddress = new SingularReferenceAttribute("address", addressRoot,
            new JoinTableMapping(new AttributeLocation("profiles", "address_id"), "id"));
        var profileRoot = new Root("profiles", List.of(profileId, profileBio, profileAddress));

        // User (depends on Profile)
        userId = new BasicAttribute("id", new AttributeLocation("users", "id"));
        userName = new BasicAttribute("name", new AttributeLocation("users", "name"));
        userEmail = new BasicAttribute("email", new AttributeLocation("users", "email"));
        userProfile = new SingularReferenceAttribute("profile", profileRoot,
            new JoinTableMapping(new AttributeLocation("users", "profile_id"), "id"));
        userRoot = new Root("users", List.of(userId, userName, userEmail, userProfile));

        // Order (depends on User)
        orderId = new BasicAttribute("id", new AttributeLocation("orders", "id"));
        orderTotal = new BasicAttribute("total", new AttributeLocation("orders", "total"));
        orderUser = new SingularReferenceAttribute("user", userRoot,
            new JoinTableMapping(new AttributeLocation("orders", "user_id"), "id"));
        orderRoot = new Root("orders", List.of(orderId, orderTotal, orderUser));

        // OrderItem (depends on Order)
        orderItemId = new BasicAttribute("id", new AttributeLocation("order_items", "id"));
        orderItemProductName = new BasicAttribute("product_name", new AttributeLocation("order_items", "product_name"));
        orderItemQuantity = new BasicAttribute("quantity", new AttributeLocation("order_items", "quantity"));
        orderItemOrder = new SingularReferenceAttribute("order", orderRoot,
            new JoinTableMapping(new AttributeLocation("order_items", "order_id"), "id"));
        orderItemRoot = new Root("order_items", List.of(orderItemId, orderItemProductName, orderItemQuantity, orderItemOrder));
    }

    static Stream<Arguments> statsTestCases() {
        return Stream.of(
            countAllOrders(),
            sumOrderTotals(),
            avgOrderTotal(),
            maxOrderTotal(),
            minOrderTotal(),
            countOrdersByUser(),
            sumTotalsByUserWithHaving(),
            avgQuantityPerProduct(),
            countOrdersWithJoin(),
            sumTotalsGroupedByCity(),
            complexAggregationWithMultipleJoins(),
            windowFunctionRowNumber(),
            windowFunctionRankWithOrderBy(),
            windowFunctionWithPartitionAndOrder(),
            expressionWithAlias(),
            multipleExpressionsWithAliases()
        );
    }

    private static Arguments countAllOrders() {
        var countExpr = new FunctionCall("count", List.of(new Path(orderId, null)));
        var query = Query.builder()
            .from(orderRoot)
            .selector(new SingleExprSelector(countExpr, false, "order_count"))
            .build();
        return Arguments.of("COUNT all orders", query, (Consumer<Result<Record>>) result -> {
            assertThat(result)
                .hasSize(1)
                .first()
                .extracting(r -> r.get("order_count", Long.class))
                .isEqualTo(3L);
        });
    }

    private static Arguments sumOrderTotals() {
        var sumExpr = new FunctionCall("sum", List.of(new Path(orderTotal, null)));
        var query = Query.builder()
            .from(orderRoot)
            .selector(new SingleExprSelector(sumExpr, false, "total_sum"))
            .build();
        return Arguments.of("SUM of order totals", query, (Consumer<Result<Record>>) result -> {
            assertThat(result)
                .hasSize(1)
                .first()
                .extracting(r -> r.get("total_sum", BigDecimal.class))
                .asInstanceOf(InstanceOfAssertFactories.BIG_DECIMAL)
                .isEqualByComparingTo(new BigDecimal("450.00"));
        });
    }

    private static Arguments avgOrderTotal() {
        var avgExpr = new FunctionCall("avg", List.of(new Path(orderTotal, null)));
        var query = Query.builder()
            .from(orderRoot)
            .selector(new SingleExprSelector(avgExpr, false, "avg_total"))
            .build();
        return Arguments.of("AVG of order totals", query, (Consumer<Result<Record>>) result -> {
            assertThat(result)
                .hasSize(1)
                .first()
                .extracting(r -> r.get("avg_total", BigDecimal.class))
                .asInstanceOf(InstanceOfAssertFactories.BIG_DECIMAL)
                .isEqualByComparingTo(new BigDecimal("150.00"));
        });
    }

    private static Arguments maxOrderTotal() {
        var maxExpr = new FunctionCall("max", List.of(new Path(orderTotal, null)));
        var query = Query.builder()
            .from(orderRoot)
            .selector(new SingleExprSelector(maxExpr, false, "max_total"))
            .build();
        return Arguments.of("MAX of order totals", query, (Consumer<Result<Record>>) result -> {
            assertThat(result)
                .hasSize(1)
                .first()
                .extracting(r -> r.get("max_total", BigDecimal.class))
                .asInstanceOf(InstanceOfAssertFactories.BIG_DECIMAL)
                .isEqualByComparingTo(new BigDecimal("200.00"));
        });
    }

    private static Arguments minOrderTotal() {
        var minExpr = new FunctionCall("min", List.of(new Path(orderTotal, null)));
        var query = Query.builder()
            .from(orderRoot)
            .selector(new SingleExprSelector(minExpr, false, "min_total"))
            .build();
        return Arguments.of("MIN of order totals", query, (Consumer<Result<Record>>) result -> {
            assertThat(result)
                .hasSize(1)
                .first()
                .extracting(r -> r.get("min_total", BigDecimal.class))
                .asInstanceOf(InstanceOfAssertFactories.BIG_DECIMAL)
                .isEqualByComparingTo(new BigDecimal("100.00"));
        });
    }

    private static Arguments countOrdersByUser() {
        var userIdPath = new Path(userId, new Path(orderUser, null));
        var countExpr = new FunctionCall("count", List.of(new Path(orderId, null)));

        var query = Query.builder()
            .from(orderRoot)
            .selector(new MultiExprSelector(Set.of(
                new SelectedExpression(userIdPath, "user_id"),
                new SelectedExpression(countExpr, "order_count")
            ), false))
            .groupBy(new GroupBy(userIdPath))
            .orderBy(new OrderBy(countExpr, false))
            .limit(1L)
            .build();
        return Arguments.of("COUNT orders grouped by user (highest first)", query, (Consumer<Result<Record>>) result -> {
            assertThat(result)
                .hasSize(1)
                .first()
                .extracting(r -> r.get("order_count", Long.class))
                .isEqualTo(2L);
        });
    }

    private static Arguments sumTotalsByUserWithHaving() {
        var userIdPath = new Path(userId, new Path(orderUser, null));
        var sumExpr = new FunctionCall("sum", List.of(new Path(orderTotal, null)));

        var havingCondition = new BinaryExpression(
            sumExpr,
            BinaryOperator.GREATER_THAN,
            new Literal(new BigDecimal("150.00"))
        );

        var query = Query.builder()
            .from(orderRoot)
            .selector(new MultiExprSelector(Set.of(
                new SelectedExpression(userIdPath, "user_id"),
                new SelectedExpression(sumExpr, "total_sum")
            ), false))
            .groupBy(new GroupBy(userIdPath))
            .having(havingCondition)
            .build();
        return Arguments.of("SUM totals by user with HAVING > 150", query, (Consumer<Result<Record>>) result -> {
            assertThat(result)
                .hasSize(1)
                .first()
                .extracting(r -> r.get("total_sum", BigDecimal.class))
                .asInstanceOf(InstanceOfAssertFactories.BIG_DECIMAL)
                .isEqualByComparingTo(new BigDecimal("300.00"));
        });
    }

    private static Arguments avgQuantityPerProduct() {
        var productPath = new Path(orderItemProductName, null);
        var avgExpr = new FunctionCall("avg", List.of(new Path(orderItemQuantity, null)));

        var query = Query.builder()
            .from(orderItemRoot)
            .selector(new MultiExprSelector(Set.of(
                new SelectedExpression(productPath, "product"),
                new SelectedExpression(avgExpr, "avg_quantity")
            ), false))
            .groupBy(new GroupBy(productPath))
            .orderBy(new OrderBy(productPath, true))
            .limit(1L)
            .build();
        return Arguments.of("AVG quantity for Book", query, (Consumer<Result<Record>>) result -> {
            assertThat(result)
                .hasSize(1)
                .first()
                .satisfies(r -> {
                    assertThat(r.get("product")).isEqualTo("Book");
                    assertThat(r.get("avg_quantity", BigDecimal.class)).isEqualByComparingTo(new BigDecimal("2.0"));
                });
        });
    }

    private static Arguments countOrdersWithJoin() {
        var userNamePath = new Path(userName, new Path(orderUser, null));
        var countExpr = new FunctionCall("count", List.of(new Path(orderId, null)));

        var whereCondition = new BinaryExpression(
            userNamePath,
            BinaryOperator.EQUALS,
            new Literal("Alice")
        );

        var query = Query.builder()
            .from(orderRoot)
            .selector(new SingleExprSelector(countExpr, false, "order_count"))
            .where(whereCondition)
            .build();
        return Arguments.of("COUNT orders with JOIN on user name", query, (Consumer<Result<Record>>) result -> {
            assertThat(result)
                .hasSize(1)
                .first()
                .extracting(r -> r.get("order_count", Long.class))
                .isEqualTo(2L);
        });
    }

    private static Arguments sumTotalsGroupedByCity() {
        var cityPath = new Path(addressCity, new Path(profileAddress, new Path(userProfile, new Path(orderUser, null))));

        var sumExpr = new FunctionCall("sum", List.of(new Path(orderTotal, null)));

        var query = Query.builder()
            .from(orderRoot)
            .selector(new MultiExprSelector(Set.of(
                new SelectedExpression(cityPath, "city"),
                new SelectedExpression(sumExpr, "total_sum")
            ), false))
            .groupBy(new GroupBy(cityPath))
            .orderBy(new OrderBy(sumExpr, false))
            .limit(1L)
            .build();
        return Arguments.of("SUM totals grouped by city (highest first)", query, (Consumer<Result<Record>>) result -> {
            assertThat(result)
                .hasSize(1)
                .first()
                .satisfies(r -> {
                    assertThat(r.get("city")).isEqualTo("New York");
                    assertThat(r.get("total_sum", BigDecimal.class)).isEqualByComparingTo(new BigDecimal("300.00"));
                });
        });
    }

    private static Arguments complexAggregationWithMultipleJoins() {
        var userNamePath = new Path(userName, new Path(orderUser, null));
        var countExpr = new FunctionCall("count", List.of(new Path(orderId, null)));
        var sumExpr = new FunctionCall("sum", List.of(new Path(orderTotal, null)));
        var avgExpr = new FunctionCall("avg", List.of(new Path(orderTotal, null)));

        var havingCondition = new BinaryExpression(
            countExpr,
            BinaryOperator.GREATER_THAN_OR_EQUAL,
            new Literal(2L)
        );

        var query = Query.builder()
            .from(orderRoot)
            .selector(new MultiExprSelector(Set.of(
                new SelectedExpression(userNamePath, "user_name"),
                new SelectedExpression(countExpr, "order_count"),
                new SelectedExpression(sumExpr, "total_sum"),
                new SelectedExpression(avgExpr, "total_avg")
            ), false))
            .groupBy(new GroupBy(userNamePath))
            .having(havingCondition)
            .orderBy(new OrderBy(sumExpr, false))
            .build();
        return Arguments.of("Multiple aggregations with JOIN and HAVING", query, (Consumer<Result<Record>>) result -> {
            assertThat(result)
                .hasSize(1)
                .first()
                .satisfies(r -> {
                    assertThat(r.get("user_name")).isEqualTo("Alice");
                    assertThat(r.get("order_count", Long.class)).isEqualTo(2L);
                    assertThat(r.get("total_sum", BigDecimal.class)).isEqualByComparingTo(new BigDecimal("300.00"));
                    assertThat(r.get("total_avg", BigDecimal.class)).isEqualByComparingTo(new BigDecimal("150.00"));
                });
        });
    }

    private static Arguments windowFunctionRowNumber() {
        var userIdPath = new Path(userId, new Path(orderUser, null));
        var orderIdPath = new Path(orderId, null);

        var rowNumWindow = new WindowFunction(
            "row_number",
            List.of(),
            new WindowSpec(List.of(userIdPath), null)
        );

        var query = Query.builder()
            .from(orderRoot)
            .selector(new MultiExprSelector(Set.of(
                new SelectedExpression(orderIdPath, "order_id"),
                new SelectedExpression(userIdPath, "user_id"),
                new SelectedExpression(rowNumWindow, "row_num")
            ), false))
            .build();
        return Arguments.of("Window function ROW_NUMBER() PARTITION BY user", query, (Consumer<Result<Record>>) result -> {
            assertThat(result)
                .hasSize(3)
                .extracting(r -> r.get("row_num", Integer.class))
                .allMatch(rowNum -> rowNum >= 1 && rowNum <= 2);
        });
    }

    private static Arguments windowFunctionRankWithOrderBy() {
        var totalPath = new Path(orderTotal, null);

        var rankWindow = new WindowFunction(
            "rank",
            List.of(),
            new WindowSpec(null, List.of(new OrderBy(totalPath, false)))
        );

        var query = Query.builder()
            .from(orderRoot)
            .selector(new MultiExprSelector(Set.of(
                new SelectedExpression(totalPath, "total"),
                new SelectedExpression(rankWindow, "rank")
            ), false))
            .build();
        return Arguments.of("Window function RANK() ORDER BY total DESC", query, (Consumer<Result<Record>>) result -> {
            assertThat(result)
                .hasSize(3)
                .extracting(r -> r.get("rank", Integer.class))
                .containsExactly(1, 2, 3);
        });
    }

    private static Arguments windowFunctionWithPartitionAndOrder() {
        var userIdPath = new Path(userId, new Path(orderUser, null));
        var totalPath = new Path(orderTotal, null);
        var orderIdPath = new Path(orderId, null);

        var denseRankWindow = new WindowFunction(
            "dense_rank",
            List.of(),
            new WindowSpec(
                List.of(userIdPath),
                List.of(new OrderBy(totalPath, false))
            )
        );

        var query = Query.builder()
            .from(orderRoot)
            .selector(new MultiExprSelector(Set.of(
                new SelectedExpression(orderIdPath, "order_id"),
                new SelectedExpression(userIdPath, "user_id"),
                new SelectedExpression(totalPath, "total"),
                new SelectedExpression(denseRankWindow, "dense_rank")
            ), false))
            .build();
        return Arguments.of("Window function DENSE_RANK() PARTITION BY user ORDER BY total", query, (Consumer<Result<Record>>) result -> {
            assertThat(result)
                .hasSize(3)
                .extracting(r -> r.get("dense_rank", Integer.class))
                .allMatch(rank -> rank >= 1 && rank <= 2);
        });
    }

    private static Arguments expressionWithAlias() {
        var totalPath = new Path(orderTotal, null);

        var query = Query.builder()
            .from(orderRoot)
            .selector(new SingleExprSelector(totalPath, false, "order_total"))
            .build();
        return Arguments.of("Expression with alias", query, (Consumer<Result<Record>>) result -> {
            assertThat(result)
                .hasSize(3)
                .extracting(r -> r.get("order_total", BigDecimal.class))
                .containsExactlyInAnyOrder(
                    new BigDecimal("100.00"),
                    new BigDecimal("200.00"),
                    new BigDecimal("150.00")
                );
        });
    }

    private static Arguments multipleExpressionsWithAliases() {
        var userIdPath = new Path(userId, new Path(orderUser, null));
        var countExpr = new FunctionCall("count", List.of(new Path(orderId, null)));
        var sumExpr = new FunctionCall("sum", List.of(new Path(orderTotal, null)));

        var query = Query.builder()
            .from(orderRoot)
            .selector(new MultiExprSelector(Set.of(
                new SelectedExpression(userIdPath, "user_id"),
                new SelectedExpression(countExpr, "order_count"),
                new SelectedExpression(sumExpr, "total_amount")
            ), false))
            .groupBy(new GroupBy(userIdPath))
            .build();
        return Arguments.of("Multiple expressions with aliases", query, (Consumer<Result<Record>>) result -> {
            assertThat(result)
                .hasSize(2)
                .extracting(
                    r -> r.get("order_count", Long.class),
                    r -> r.get("total_amount", BigDecimal.class)
                )
                .containsExactlyInAnyOrder(
                    tuple(2L, new BigDecimal("300.00")),
                    tuple(1L, new BigDecimal("150.00"))
                );
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
        dsl.execute("insert into order_items (order_id, product_name, quantity) values (1, 'Book', 2), (1, 'Pen', 5), (2, 'Laptop', 1), (3, 'Mouse', 3)");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("statsTestCases")
    void testStatsQueryTransformation(String testName, Query query, Consumer<Result<Record>> resultValidator) {
        var sql = transformer.transform(query);
        println("Generated SQL for test '" + testName + "':\n" + sql + "\n");
        var result = (Result<Record>) dsl.fetch(sql);
        resultValidator.accept(result);
    }
}
