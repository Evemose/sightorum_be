package com.rorm.engine;

import com.rorm.metamodel.*;
import com.rorm.query.Expression.BinaryExpression;
import com.rorm.query.Expression.Literal;
import com.rorm.query.*;
import com.rorm.query.Join.JoinType;
import com.rorm.query.Selector.MultiExprSelector;
import com.rorm.query.Selector.SingleExprSelector;
import com.rorm.testutil.AbstractPostgresTest;
import com.rorm.testutil.TestHandlerRegistry;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@Testcontainers
@DisplayName("JoinedRoot Query Integration")
class JoinedRootQueryTest extends AbstractPostgresTest {

    private static Root customerRoot;
    private static Root orderRoot;
    private static Root productRoot;

    private static BasicAttribute customerId;
    private static BasicAttribute customerName;
    private static BasicAttribute orderId;
    private static BasicAttribute orderTotal;
    private static BasicAttribute orderCustomerId;
    private static SingularReferenceAttribute orderCustomer;
    private static BasicAttribute productId;
    private static BasicAttribute productName;

    private DSLContext dsl;
    private QueryTransformer transformer;

    @BeforeAll
    static void setupMetamodel() {
        // Product root
        productId = new BasicAttribute("id", new AttributeLocation("products", "id"), new DataType.NumericType(19, 0));
        productName = new BasicAttribute("name", new AttributeLocation("products", "name"), new DataType.StringType());
        productRoot = new Root("products", List.of(productId, productName), IdDescriptor.longId("products"));

        // Customer root
        customerId = new BasicAttribute("id", new AttributeLocation("customers", "id"), new DataType.NumericType(19, 0));
        customerName = new BasicAttribute("name", new AttributeLocation("customers", "name"), new DataType.StringType());
        customerRoot = new Root("customers", List.of(customerId, customerName), IdDescriptor.longId("customers"));

        // Order root
        orderId = new BasicAttribute("id", new AttributeLocation("orders", "id"), new DataType.NumericType(19, 0));
        orderTotal = new BasicAttribute("total", new AttributeLocation("orders", "total"), new DataType.NumericType(10, 2));
        orderCustomerId = new BasicAttribute("customer_id", new AttributeLocation("orders", "customer_id"), new DataType.NumericType(19, 0));
        orderCustomer = new SingularReferenceAttribute("customer", customerRoot,
            new ReferenceAttribute.SameTableColumn("customer_id"));
        orderRoot = new Root("orders", List.of(orderId, orderTotal, orderCustomerId, orderCustomer), IdDescriptor.longId("orders"));
    }

    @BeforeEach
    @SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
    void setUp() {
        var schemaName = "test_" + UUID.randomUUID().toString().replace("-", "_");

        dsl = DSL.using(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        var handlerRegistry = TestHandlerRegistry.createWithAllBuiltIns();
        var expressionTransformer = new ExpressionTransformer(handlerRegistry);
        transformer = new QueryTransformer(dsl, expressionTransformer);

        dsl.execute("create schema " + schemaName);
        dsl.execute("set search_path to " + schemaName);

        dsl.execute("""
                create table customers (
                    id bigserial primary key,
                    name varchar(255)
                )
            """);

        dsl.execute("""
                create table orders (
                    id bigserial primary key,
                    customer_id bigint references customers(id),
                    total decimal(10,2)
                )
            """);

        dsl.execute("""
                create table products (
                    id bigserial primary key,
                    name varchar(255)
                )
            """);

        dsl.execute("insert into customers (name) values ('Alice'), ('Bob'), ('Charlie')");
        dsl.execute("insert into orders (customer_id, total) values (1, 100.00), (1, 200.00), (2, 150.00)");
        dsl.execute("insert into products (name) values ('Widget'), ('Gadget')");
    }

    private LinkedHashSet<Join> joins(Join... joins) {
        return new LinkedHashSet<>(List.of(joins));
    }

    @Nested
    @DisplayName("Explicit joins with JoinedRoot")
    class ExplicitJoinsWithJoinedRoot {

        @Test
        @DisplayName("joins customers with orders using explicit INNER JOIN")
        void joinsCustomersWithOrdersUsingInnerJoin() {
            var joinedOrders = AliasedRoot.of(orderRoot, "o");
            var orderTotalPath = new Path(orderTotal, new Path(joinedOrders, null));
            var customerIdPath = new Path(customerId, null);

            // Join condition: customers.id = orders.customer_id
            var orderCustomerIdPath = new Path(orderCustomerId, new Path(joinedOrders, null));
            var joinCondition = new BinaryExpression(customerIdPath, StandardOperator.Binary.EQUALS.identifier(), orderCustomerIdPath);

            var query = Query.builder()
                .from(AliasedRoot.of(customerRoot))
                .joins(joins(new Join(joinedOrders, JoinType.INNER, joinCondition)))
                .selector(new MultiExprSelector(Set.of(
                    new SelectedExpression(new Path(customerName, null), "customer_name"),
                    new SelectedExpression(orderTotalPath, "order_total")
                ), false))
                .build();

            var sql = transformer.transform(query);
            var sqlString = sql.getSQL().toLowerCase();

            assertThat(sqlString)
                .as("SQL should contain JOIN for orders table")
                .contains("join")
                .contains("orders");

            @SuppressWarnings("unchecked")
            var result = (Result<Record>) dsl.fetch(sql);

            assertThat(result)
                .hasSize(3)
                .extracting(r -> r.get("customer_name"), r -> r.get("order_total"))
                .containsExactlyInAnyOrder(
                    tuple("Alice", new java.math.BigDecimal("100.00")),
                    tuple("Alice", new java.math.BigDecimal("200.00")),
                    tuple("Bob", new java.math.BigDecimal("150.00"))
                );
        }

        @Test
        @DisplayName("joins customers with orders using explicit LEFT JOIN")
        void joinsCustomersWithOrdersUsingLeftJoin() {
            var joinedOrders = AliasedRoot.of(orderRoot, "o");
            var orderTotalPath = new Path(orderTotal, new Path(joinedOrders, null));
            var customerIdPath = new Path(customerId, null);

            var orderCustomerIdPath = new Path(orderCustomerId, new Path(joinedOrders, null));
            var joinCondition = new BinaryExpression(customerIdPath, StandardOperator.Binary.EQUALS.identifier(), orderCustomerIdPath);

            var query = Query.builder()
                .from(AliasedRoot.of(customerRoot))
                .joins(joins(new Join(joinedOrders, JoinType.LEFT, joinCondition)))
                .selector(new MultiExprSelector(Set.of(
                    new SelectedExpression(new Path(customerName, null), "customer_name"),
                    new SelectedExpression(orderTotalPath, "order_total")
                ), false))
                .build();

            var sql = transformer.transform(query);
            var sqlString = sql.getSQL().toLowerCase();

            assertThat(sqlString)
                .as("SQL should contain LEFT JOIN")
                .contains("left");

            @SuppressWarnings("unchecked")
            var result = (Result<Record>) dsl.fetch(sql);

            // Charlie has no orders, so should appear with null order_total
            assertThat(result)
                .hasSize(4)
                .extracting(r -> r.get("customer_name"))
                .contains("Alice", "Bob", "Charlie");

            // Verify Charlie has null order_total
            assertThat(result)
                .filteredOn(r -> "Charlie".equals(r.get("customer_name")))
                .singleElement()
                .satisfies(r -> assertThat(r.get("order_total")).isNull());
        }

        @Test
        @DisplayName("uses JoinedRoot alias for attribute paths in SELECT")
        void usesJoinedRootAliasInSelect() {
            var joinedOrders = AliasedRoot.of(orderRoot, "ord");
            var orderTotalPath = new Path(orderTotal, new Path(joinedOrders, null));
            var customerIdPath = new Path(customerId, null);

            var orderCustomerIdPath = new Path(orderCustomerId, new Path(joinedOrders, null));
            var joinCondition = new BinaryExpression(customerIdPath, StandardOperator.Binary.EQUALS.identifier(), orderCustomerIdPath);

            var query = Query.builder()
                .from(AliasedRoot.of(customerRoot))
                .joins(joins(new Join(joinedOrders, JoinType.INNER, joinCondition)))
                .selector(new SingleExprSelector(orderTotalPath, false, "total"))
                .build();

            var sql = transformer.transform(query);

            @SuppressWarnings("unchecked")
            var result = (Result<Record>) dsl.fetch(sql);

            assertThat(result)
                .hasSize(3)
                .extracting(r -> r.get("total"))
                .containsExactlyInAnyOrder(
                    new java.math.BigDecimal("100.00"),
                    new java.math.BigDecimal("200.00"),
                    new java.math.BigDecimal("150.00")
                );
        }

        @Test
        @DisplayName("uses JoinedRoot alias in WHERE clause")
        void usesJoinedRootAliasInWhereClause() {
            var joinedOrders = AliasedRoot.of(orderRoot, "o");
            var orderTotalPath = new Path(orderTotal, new Path(joinedOrders, null));
            var customerIdPath = new Path(customerId, null);

            var orderCustomerIdPath = new Path(orderCustomerId, new Path(joinedOrders, null));
            var joinCondition = new BinaryExpression(customerIdPath, StandardOperator.Binary.EQUALS.identifier(), orderCustomerIdPath);

            // WHERE o.total > 100
            var whereCondition = new BinaryExpression(
                orderTotalPath,
                StandardOperator.Binary.GREATER_THAN.identifier(),
                new Literal(new java.math.BigDecimal("100"))
            );

            var query = Query.builder()
                .from(AliasedRoot.of(customerRoot))
                .joins(joins(new Join(joinedOrders, JoinType.INNER, joinCondition)))
                .selector(new MultiExprSelector(Set.of(
                    new SelectedExpression(new Path(customerName, null), "customer_name"),
                    new SelectedExpression(orderTotalPath, "order_total")
                ), false))
                .where(whereCondition)
                .build();

            var sql = transformer.transform(query);

            @SuppressWarnings("unchecked")
            var result = (Result<Record>) dsl.fetch(sql);

            assertThat(result)
                .hasSize(2)
                .extracting(r -> r.get("customer_name"), r -> r.get("order_total"))
                .containsExactlyInAnyOrder(
                    tuple("Alice", new java.math.BigDecimal("200.00")),
                    tuple("Bob", new java.math.BigDecimal("150.00"))
                );
        }

        @Test
        @DisplayName("supports path navigation inside explicit join ON condition")
        void supportsPathNavigationInsideExplicitJoinOnCondition() {
            var joinedCustomers = AliasedRoot.of(customerRoot, "c");
            var orderCustomerNamePath = new Path(customerName, new Path(orderCustomer, null));
            var joinedCustomerNamePath = new Path(customerName, new Path(joinedCustomers, null));
            var joinCondition = new BinaryExpression(
                orderCustomerNamePath,
                StandardOperator.Binary.EQUALS.identifier(),
                joinedCustomerNamePath
            );

            var query = Query.builder()
                .from(AliasedRoot.of(orderRoot))
                .joins(joins(new Join(joinedCustomers, JoinType.INNER, joinCondition)))
                .selector(new MultiExprSelector(Set.of(
                    new SelectedExpression(joinedCustomerNamePath, "customer_name"),
                    new SelectedExpression(new Path(orderTotal, null), "order_total")
                ), false))
                .build();

            var sql = transformer.transform(query);

            @SuppressWarnings("unchecked")
            var result = (Result<Record>) dsl.fetch(sql);

            assertThat(result)
                .hasSize(3)
                .extracting(r -> r.get("customer_name"), r -> r.get("order_total"))
                .containsExactlyInAnyOrder(
                    tuple("Alice", new java.math.BigDecimal("100.00")),
                    tuple("Alice", new java.math.BigDecimal("200.00")),
                    tuple("Bob", new java.math.BigDecimal("150.00"))
                );
        }
    }

    @Nested
    @DisplayName("Multiple explicit joins")
    class MultipleExplicitJoins {

        @Test
        @DisplayName("joins with multiple tables using different aliases")
        void joinsWithMultipleTablesUsingDifferentAliases() {
            var joinedOrders = AliasedRoot.of(orderRoot, "o");
            var joinedProducts = AliasedRoot.of(productRoot, "p");

            var customerIdPath = new Path(customerId, null);
            var orderCustomerIdPath = new Path(orderCustomerId, new Path(joinedOrders, null));
            var ordersJoinCondition = new BinaryExpression(customerIdPath, StandardOperator.Binary.EQUALS.identifier(), orderCustomerIdPath);

            // Cross join with products (no condition needed)
            var joins = new LinkedHashSet<Join>();
            joins.add(new Join(joinedOrders, JoinType.INNER, ordersJoinCondition));
            joins.add(new Join(joinedProducts, JoinType.CROSS, null));

            var productNamePath = new Path(productName, new Path(joinedProducts, null));

            var query = Query.builder()
                .from(AliasedRoot.of(customerRoot))
                .joins(joins)
                .selector(new MultiExprSelector(Set.of(
                    new SelectedExpression(new Path(customerName, null), "customer_name"),
                    new SelectedExpression(productNamePath, "product_name")
                ), false))
                .build();

            var sql = transformer.transform(query);
            var sqlString = sql.getSQL().toLowerCase();

            assertThat(sqlString)
                .as("SQL should contain JOIN and CROSS JOIN")
                .contains("join")
                .contains("cross join");

            @SuppressWarnings("unchecked")
            var result = (Result<Record>) dsl.fetch(sql);

            // 3 order rows * 2 products = 6 rows
            assertThat(result).hasSize(6);
        }

        @Test
        @DisplayName("self-join using same table with different aliases")
        void selfJoinUsingSameTableWithDifferentAliases() {
            var c1 = AliasedRoot.of(customerRoot, "c1");
            var c2 = AliasedRoot.of(customerRoot, "c2");

            // Cross join customers with itself
            var joins = new LinkedHashSet<Join>();
            joins.add(new Join(c1, JoinType.CROSS, null));
            joins.add(new Join(c2, JoinType.CROSS, null));

            var name1Path = new Path(customerName, new Path(c1, null));
            var name2Path = new Path(customerName, new Path(c2, null));

            var query = Query.builder()
                .from(AliasedRoot.of(customerRoot))
                .joins(joins)
                .selector(new MultiExprSelector(Set.of(
                    new SelectedExpression(name1Path, "name1"),
                    new SelectedExpression(name2Path, "name2")
                ), false))
                .build();

            var sql = transformer.transform(query);

            @SuppressWarnings("unchecked")
            var result = (Result<Record>) dsl.fetch(sql);

            // 3 customers from root * 3 from c1 * 3 from c2 = 27 rows
            assertThat(result).hasSize(27);
        }
    }

    @Nested
    @DisplayName("FROM root aliasing")
    class FromRootAliasing {

        @Test
        @DisplayName("aliases FROM root and references it in expressions")
        void aliasesFromRootAndReferencesInExpressions() {
            var aliasedCustomer = AliasedRoot.of(customerRoot, "c");
            var customerNamePath = new Path(customerName, new Path(aliasedCustomer, null));

            var query = Query.builder()
                .from(AliasedRoot.of(customerRoot, "c"))
                .selector(new SingleExprSelector(customerNamePath, false, "name"))
                .build();

            var sql = transformer.transform(query);

            @SuppressWarnings("unchecked")
            var result = (Result<Record>) dsl.fetch(sql);

            assertThat(result)
                .hasSize(3)
                .extracting(r -> r.get("name"))
                .containsExactlyInAnyOrder("Alice", "Bob", "Charlie");
        }

        @Test
        @DisplayName("uses FROM alias with explicit join")
        void usesFromAliasWithExplicitJoin() {
            var aliasedCustomer = AliasedRoot.of(customerRoot, "c");
            var joinedOrders = AliasedRoot.of(orderRoot, "o");

            var customerIdPath = new Path(customerId, new Path(aliasedCustomer, null));
            var orderCustomerIdPath = new Path(orderCustomerId, new Path(joinedOrders, null));
            var joinCondition = new BinaryExpression(customerIdPath, StandardOperator.Binary.EQUALS.identifier(), orderCustomerIdPath);

            var customerNamePath = new Path(customerName, new Path(aliasedCustomer, null));
            var orderTotalPath = new Path(orderTotal, new Path(joinedOrders, null));

            var query = Query.builder()
                .from(AliasedRoot.of(customerRoot, "c"))
                .joins(joins(new Join(joinedOrders, JoinType.INNER, joinCondition)))
                .selector(new MultiExprSelector(Set.of(
                    new SelectedExpression(customerNamePath, "customer_name"),
                    new SelectedExpression(orderTotalPath, "order_total")
                ), false))
                .build();

            var sql = transformer.transform(query);

            @SuppressWarnings("unchecked")
            var result = (Result<Record>) dsl.fetch(sql);

            assertThat(result)
                .hasSize(3)
                .extracting(r -> r.get("customer_name"), r -> r.get("order_total"))
                .containsExactlyInAnyOrder(
                    tuple("Alice", new java.math.BigDecimal("100.00")),
                    tuple("Alice", new java.math.BigDecimal("200.00")),
                    tuple("Bob", new java.math.BigDecimal("150.00"))
                );
        }

        @Test
        @DisplayName("uses FROM alias in WHERE clause")
        void usesFromAliasInWhereClause() {
            var aliasedCustomer = AliasedRoot.of(customerRoot, "c");
            var customerNamePath = new Path(customerName, new Path(aliasedCustomer, null));

            var whereCondition = new BinaryExpression(
                customerNamePath,
                StandardOperator.Binary.EQUALS.identifier(),
                new Literal("Alice")
            );

            var query = Query.builder()
                .from(AliasedRoot.of(customerRoot, "c"))
                .selector(new SingleExprSelector(customerNamePath, false, "name"))
                .where(whereCondition)
                .build();

            var sql = transformer.transform(query);

            @SuppressWarnings("unchecked")
            var result = (Result<Record>) dsl.fetch(sql);

            assertThat(result)
                .singleElement()
                .satisfies(r -> assertThat(r.get("name")).isEqualTo("Alice"));
        }
    }

    @Nested
    @DisplayName("Error handling for JoinedRoot")
    class ErrorHandlingForAliasedRoot {

        @Test
        @DisplayName("throws IllegalArgumentException when selecting JoinedRoot directly")
        void throwsWhenSelectingJoinedRootDirectly() {
            var joinedOrders = AliasedRoot.of(orderRoot, "o");
            var joinedRootPath = new Path(joinedOrders, null);

            var customerIdPath = new Path(customerId, null);
            var orderCustomerIdPath = new Path(orderCustomerId, new Path(joinedOrders, null));
            var joinCondition = new BinaryExpression(customerIdPath, StandardOperator.Binary.EQUALS.identifier(), orderCustomerIdPath);

            var query = Query.builder()
                .from(AliasedRoot.of(customerRoot))
                .joins(joins(new Join(joinedOrders, JoinType.INNER, joinCondition)))
                .selector(new SingleExprSelector(joinedRootPath, false, null))
                .build();

            assertThatThrownBy(() -> transformer.transform(query))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cannot select a JoinedRoot directly");
        }

        @Test
        @DisplayName("throws when non-CROSS join has no ON condition")
        void throwsWhenNonCrossJoinHasNoCondition() {
            var joinedOrders = AliasedRoot.of(orderRoot, "o");

            assertThatThrownBy(() -> new Join(joinedOrders, JoinType.INNER, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ON condition is required");
        }

        @Test
        @DisplayName("throws when JoinedRoot is null in Join")
        void throwsWhenJoinedRootIsNull() {
            var dummyCondition = new BinaryExpression(
                new Path(customerId, null),
                StandardOperator.Binary.EQUALS.identifier(),
                new Literal(1)
            );

            assertThatThrownBy(() -> new Join(null, JoinType.INNER, dummyCondition))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JoinedRoot cannot be null");
        }
    }
}
