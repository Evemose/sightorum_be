package com.rorm.engine;

import com.rorm.metamodel.*;
import com.rorm.metamodel.ReferenceAttribute.InverseRootTableColumn;
import com.rorm.metamodel.ReferenceAttribute.JoinTableMapping;
import com.rorm.query.Expression.BinaryExpression;
import com.rorm.query.Expression.FunctionCall;
import com.rorm.query.Expression.Literal;
import com.rorm.query.*;
import com.rorm.query.Selector.MultiExprSelector;
import com.rorm.query.Selector.SingleExprSelector;
import com.rorm.testutil.AbstractPostgresTest;
import com.rorm.testutil.TestHandlerRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection", "FieldCanBeLocal"})
class SubqueryTransformerTest extends AbstractPostgresTest {

    private static Root customerRoot;
    private static Root orderRoot;
    private static Root orderItemRoot;

    private static BasicAttribute customerId;
    private static BasicAttribute customerName;
    private static BasicAttribute customerEmail;

    private static BasicAttribute orderId;
    private static BasicAttribute orderTotal;
    private static BasicAttribute orderCustomerId;
    private static SingularReferenceAttribute orderCustomer;

    private static BasicAttribute itemId;
    private static BasicAttribute itemName;
    private static BasicAttribute itemPrice;
    private static BasicAttribute itemOrderId;
    private static SingularReferenceAttribute itemOrder;

    private static PluralReferenceAttribute customerOrders;

    private QueryTransformer transformer;

    @BeforeAll
    static void setupMetamodel() {
        // Customer root
        customerId = new BasicAttribute("id", new AttributeLocation("customers", "id"), new DataType.NumericType(19, 0));
        customerName = new BasicAttribute("name", new AttributeLocation("customers", "name"), new DataType.StringType());
        customerEmail = new BasicAttribute("email", new AttributeLocation("customers", "email"), new DataType.StringType());

        // Order root (forward declaration for circular reference)
        orderId = new BasicAttribute("id", new AttributeLocation("orders", "id"), new DataType.NumericType(19, 0));
        orderTotal = new BasicAttribute("total", new AttributeLocation("orders", "total"), new DataType.NumericType(10, 2));
        orderCustomerId = new BasicAttribute("customer_id", new AttributeLocation("orders", "customer_id"), new DataType.NumericType(19, 0));

        // Order item root
        itemId = new BasicAttribute("id", new AttributeLocation("order_items", "id"), new DataType.NumericType(19, 0));
        itemName = new BasicAttribute("name", new AttributeLocation("order_items", "name"), new DataType.StringType());
        itemPrice = new BasicAttribute("price", new AttributeLocation("order_items", "price"), new DataType.NumericType(10, 2));
        itemOrderId = new BasicAttribute("order_id", new AttributeLocation("order_items", "order_id"), new DataType.NumericType(19, 0));

        // Create roots (order first for references)
        orderRoot = new Root("orders", List.of(orderId, orderTotal, orderCustomerId), IdDescriptor.longId("orders"));
        orderItemRoot = new Root("order_items", List.of(itemId, itemName, itemPrice, itemOrderId), IdDescriptor.longId("order_items"));

        // Set up references
        orderCustomer = new SingularReferenceAttribute("customer", null,
            new JoinTableMapping(new AttributeLocation("orders", "customer_id"), "id"));

        itemOrder = new SingularReferenceAttribute("order", orderRoot,
            new JoinTableMapping(new AttributeLocation("order_items", "order_id"), "id"));

        // Customer with orders reference
        customerOrders = new PluralReferenceAttribute("orders", orderRoot,
            new InverseRootTableColumn("customer_id"));

        customerRoot = new Root("customers", List.of(customerId, customerName, customerEmail, customerOrders), IdDescriptor.longId("customers"));

        // Update orderCustomer to reference customerRoot
        orderCustomer = new SingularReferenceAttribute("customer", customerRoot,
            new JoinTableMapping(new AttributeLocation("orders", "customer_id"), "id"));

        // Recreate orderRoot with customer reference
        orderRoot = new Root("orders", List.of(orderId, orderTotal, orderCustomerId, orderCustomer), IdDescriptor.longId("orders"));

        // Update itemOrder to reference updated orderRoot
        itemOrder = new SingularReferenceAttribute("order", orderRoot,
            new JoinTableMapping(new AttributeLocation("order_items", "order_id"), "id"));
        orderItemRoot = new Root("order_items", List.of(itemId, itemName, itemPrice, itemOrderId, itemOrder), IdDescriptor.longId("order_items"));
    }

    @Override
    protected void afterDatabaseSetup() {
        var handlerRegistry = TestHandlerRegistry.createWithAllBuiltIns();
        var expressionTransformer = new ExpressionTransformer(handlerRegistry);
        var subqueryTransformer = new SubqueryTransformer(expressionTransformer);
        expressionTransformer.setSubqueryTransformer(subqueryTransformer);
        transformer = new QueryTransformer(dsl, expressionTransformer);

        dsl.execute("""
                create table customers (
                    id bigserial primary key,
                    name varchar(100) not null,
                    email varchar(100)
                )
            """);

        dsl.execute("""
                create table orders (
                    id bigserial primary key,
                    customer_id bigint references customers(id),
                    total decimal(10,2) not null
                )
            """);

        dsl.execute("""
                create table order_items (
                    id bigserial primary key,
                    order_id bigint references orders(id),
                    name varchar(100) not null,
                    price decimal(10,2) not null
                )
            """);

        // Insert test data
        dsl.execute("insert into customers (id, name, email) values (1, 'Alice', 'alice@example.com')");
        dsl.execute("insert into customers (id, name, email) values (2, 'Bob', 'bob@example.com')");
        dsl.execute("insert into customers (id, name, email) values (3, 'Charlie', 'charlie@example.com')");

        dsl.execute("insert into orders (id, customer_id, total) values (1, 1, 100.00)");
        dsl.execute("insert into orders (id, customer_id, total) values (2, 1, 200.00)");
        dsl.execute("insert into orders (id, customer_id, total) values (3, 2, 150.00)");
        dsl.execute("insert into orders (id, customer_id, total) values (4, 2, 350.00)");
        // Charlie has no orders

        dsl.execute("insert into order_items (id, order_id, name, price) values (1, 1, 'Widget', 50.00)");
        dsl.execute("insert into order_items (id, order_id, name, price) values (2, 1, 'Gadget', 50.00)");
        dsl.execute("insert into order_items (id, order_id, name, price) values (3, 2, 'Gizmo', 200.00)");
        dsl.execute("insert into order_items (id, order_id, name, price) values (4, 3, 'Thingamajig', 150.00)");
        dsl.execute("insert into order_items (id, order_id, name, price) values (5, 4, 'Doohickey', 350.00)");
    }

    @Nested
    @DisplayName("Simple Subqueries")
    class SimpleSubqueries {

        @Test
        @DisplayName("Scalar subquery in SELECT - average order total")
        void scalarSubqueryInSelect() {
            // SELECT name, (SELECT AVG(total) FROM orders) as avg_total FROM customers
            var avgSubquery = new Subquery(Query.builder()
                .from(AliasedRoot.of(orderRoot))
                .selector(new SingleExprSelector(
                    new FunctionCall("AVG", List.of(new Path(orderTotal, null))),
                    false, null))
                .build());

            var query = Query.builder()
                .from(AliasedRoot.of(customerRoot))
                .selector(new MultiExprSelector(
                    Set.of(
                        new SelectedExpression(new Path(customerName, null), "name"),
                        new SelectedExpression(avgSubquery, "avg_total")
                    ),
                    false
                ))
                .orderBy(OrderBy.asc(new Path(customerId, null)))
                .build();

            var result = dsl.fetch(transformer.transform(query));

            assertThat(result).extracting(r -> r.get("name")).containsExactly("Alice", "Bob", "Charlie");
            assertThat(result.getFirst().get("avg_total", BigDecimal.class))
                .isEqualByComparingTo(new BigDecimal("200.00"));
        }

        @Test
        @DisplayName("Subquery in WHERE - filter customers with large orders using IN")
        void subqueryInWhereClause() {
            // SELECT name FROM customers WHERE id IN (SELECT customer_id FROM orders WHERE total > 200)
            var inSubquery = new Subquery(Query.builder()
                .from(AliasedRoot.of(orderRoot))
                .selector(new SingleExprSelector(new Path(orderCustomerId, null), false, null))
                .where(new BinaryExpression(
                    new Path(orderTotal, null),
                    StandardOperator.Binary.GREATER_THAN.identifier(),
                    new Literal(new BigDecimal("200"))
                ))
                .build());

            var query = Query.builder()
                .from(AliasedRoot.of(customerRoot))
                .selector(new MultiExprSelector(
                    Set.of(new SelectedExpression(new Path(customerName, null), "name")),
                    false
                ))
                .where(new BinaryExpression(
                    new Path(customerId, null),
                    StandardOperator.Binary.IN.identifier(),
                    inSubquery
                ))
                .orderBy(OrderBy.asc(new Path(customerName, null)))
                .build();

            // Bob has order with total 350
            assertThat(dsl.fetch(transformer.transform(query)))
                .extracting(r -> r.get("name"))
                .containsExactly("Bob");
        }

        @Test
        @DisplayName("Subquery with IN operator")
        void subqueryWithInOperator() {
            // SELECT name FROM customers WHERE id IN (SELECT customer_id FROM orders WHERE total > 100)
            var inSubquery = new Subquery(Query.builder()
                .from(AliasedRoot.of(orderRoot))
                .selector(new SingleExprSelector(new Path(orderCustomerId, null), false, null))
                .where(new BinaryExpression(
                    new Path(orderTotal, null),
                    StandardOperator.Binary.GREATER_THAN.identifier(),
                    new Literal(new BigDecimal("100"))
                ))
                .build());

            var query = Query.builder()
                .from(AliasedRoot.of(customerRoot))
                .selector(new MultiExprSelector(
                    Set.of(new SelectedExpression(new Path(customerName, null), "name")),
                    false
                ))
                .where(new BinaryExpression(
                    new Path(customerId, null),
                    StandardOperator.Binary.IN.identifier(),
                    inSubquery
                ))
                .orderBy(OrderBy.asc(new Path(customerName, null)))
                .build();

            assertThat(dsl.fetch(transformer.transform(query)))
                .extracting(r -> r.get("name"))
                .containsExactly("Alice", "Bob");
        }
    }

    @Nested
    @DisplayName("Correlated Subqueries")
    class CorrelatedSubqueries {

        @Test
        @DisplayName("Correlated subquery with OuterRef - count orders per customer")
        void correlatedSubqueryWithOuterRef() {
            // SELECT name, (SELECT COUNT(*) FROM orders WHERE customer_id = c.id) as order_count
            // FROM customers c
            var customerIdPath = new Path(customerId, null);
            var outerCustomerId = customerIdPath;

            var countSubquery = new Subquery(Query.builder()
                .from(AliasedRoot.of(orderRoot))
                .selector(new SingleExprSelector(
                    new FunctionCall("COUNT", List.of(new Literal("*"))),
                    false, null))
                .where(new BinaryExpression(
                    new Path(orderCustomerId, null),
                    StandardOperator.Binary.EQUALS.identifier(),
                    outerCustomerId
                ))
                .build());

            var query = Query.builder()
                .from(AliasedRoot.of(customerRoot))
                .selector(new MultiExprSelector(
                    Set.of(
                        new SelectedExpression(new Path(customerName, null), "name"),
                        new SelectedExpression(countSubquery, "order_count")
                    ),
                    false
                ))
                .orderBy(OrderBy.asc(new Path(customerId, null)))
                .build();

            assertThat(dsl.fetch(transformer.transform(query)))
                .extracting(r -> r.get("name"), r -> r.get("order_count"))
                .containsExactly(
                    tuple("Alice", 2L),
                    tuple("Bob", 2L),
                    tuple("Charlie", 0L)
                );
        }

        @Test
        @DisplayName("Correlated subquery with max - find max order per customer")
        void correlatedSubqueryWithMax() {
            // SELECT name, (SELECT MAX(total) FROM orders WHERE customer_id = c.id) as max_order
            // FROM customers c
            var customerIdPath = new Path(customerId, null);
            var outerCustomerId = customerIdPath;

            var maxSubquery = new Subquery(Query.builder()
                .from(AliasedRoot.of(orderRoot))
                .selector(new SingleExprSelector(
                    new FunctionCall("MAX", List.of(new Path(orderTotal, null))),
                    false, null))
                .where(new BinaryExpression(
                    new Path(orderCustomerId, null),
                    StandardOperator.Binary.EQUALS.identifier(),
                    outerCustomerId
                ))
                .build());

            var query = Query.builder()
                .from(AliasedRoot.of(customerRoot))
                .selector(new MultiExprSelector(
                    Set.of(
                        new SelectedExpression(new Path(customerName, null), "name"),
                        new SelectedExpression(maxSubquery, "max_order")
                    ),
                    false
                ))
                .orderBy(OrderBy.asc(new Path(customerId, null)))
                .build();

            var result = dsl.fetch(transformer.transform(query));

            assertThat(result).extracting(r -> r.get("name")).containsExactly("Alice", "Bob", "Charlie");
            assertThat(result.getFirst().get("max_order", BigDecimal.class)).isEqualByComparingTo("200.00");
            assertThat(result.get(1).get("max_order", BigDecimal.class)).isEqualByComparingTo("350.00");
            assertThat(result.getLast().get("max_order")).isNull();
        }
    }

    @Nested
    @DisplayName("Nested Correlated Subqueries")
    class NestedCorrelatedSubqueries {

        @Test
        @DisplayName("Double-nested subquery with OuterRef at different levels")
        void doubleNestedSubqueryWithOuterRef() {
            // SELECT name,
            //   (SELECT COUNT(*) FROM orders o WHERE o.customer_id = c.id
            //     AND o.id IN (SELECT order_id FROM order_items i WHERE i.price > 100))
            //   as expensive_order_count
            // FROM customers c

            var customerIdPath = new Path(customerId, null);

            // Innermost subquery: SELECT order_id FROM order_items WHERE price > 100
            var itemsSubquery = new Subquery(Query.builder()
                .from(AliasedRoot.of(orderItemRoot))
                .selector(new SingleExprSelector(new Path(itemOrderId, null), false, null))
                .where(new BinaryExpression(
                    new Path(itemPrice, null),
                    StandardOperator.Binary.GREATER_THAN.identifier(),
                    new Literal(new BigDecimal("100"))
                ))
                .build());

            // Middle subquery: SELECT COUNT(*) FROM orders WHERE customer_id = c.id AND id IN (...)
            var ordersSubquery = new Subquery(Query.builder()
                .from(AliasedRoot.of(orderRoot))
                .selector(new SingleExprSelector(
                    new FunctionCall("COUNT", List.of(new Literal("*"))),
                    false, null))
                .where(new BinaryExpression(
                    new BinaryExpression(
                        new Path(orderCustomerId, null),
                        StandardOperator.Binary.EQUALS.identifier(),
                        customerIdPath
                    ),
                    StandardOperator.Binary.AND.identifier(),
                    new BinaryExpression(
                        new Path(orderId, null),
                        StandardOperator.Binary.IN.identifier(),
                        itemsSubquery
                    )
                ))
                .build());

            var query = Query.builder()
                .from(AliasedRoot.of(customerRoot))
                .selector(new MultiExprSelector(
                    Set.of(
                        new SelectedExpression(new Path(customerName, null), "name"),
                        new SelectedExpression(ordersSubquery, "expensive_order_count")
                    ),
                    false
                ))
                .orderBy(OrderBy.asc(new Path(customerId, null)))
                .build();

            // Alice: orders 1 (items: 50, 50) and 2 (items: 200) -> 1 expensive order (order 2)
            // Bob: orders 3 (items: 150) and 4 (items: 350) -> 2 expensive orders
            // Charlie: no orders -> 0
            assertThat(dsl.fetch(transformer.transform(query)))
                .extracting(r -> r.get("name"), r -> r.get("expensive_order_count"))
                .containsExactly(
                    tuple("Alice", 1L),
                    tuple("Bob", 2L),
                    tuple("Charlie", 0L)
                );
        }

        @Test
        @DisplayName("Nested subquery referencing grandparent with OuterRef depth=2")
        void nestedSubqueryWithGrandparentReference() {
            // SELECT name,
            //   (SELECT SUM((SELECT SUM(price) FROM order_items WHERE order_id = o.id))
            //    FROM orders o WHERE o.customer_id = c.id)
            //   as total_items_value
            // FROM customers c
            //
            // This tests OuterRef at depth=1 (from items to orders) within another subquery

            var customerIdPath = new Path(customerId, null);
            var orderIdPath = new Path(orderId, null);

            // Innermost subquery: SELECT SUM(price) FROM order_items WHERE order_id = o.id
            var itemsSumSubquery = new Subquery(Query.builder()
                .from(AliasedRoot.of(orderItemRoot))
                .selector(new SingleExprSelector(
                    new FunctionCall("SUM", List.of(new Path(itemPrice, null))),
                    false, null))
                .where(new BinaryExpression(
                    new Path(itemOrderId, null),
                    StandardOperator.Binary.EQUALS.identifier(),
                    orderIdPath
                ))
                .build());

            // Middle subquery: SELECT SUM(...) FROM orders WHERE customer_id = c.id
            var ordersSubquery = new Subquery(Query.builder()
                .from(AliasedRoot.of(orderRoot))
                .selector(new SingleExprSelector(
                    new FunctionCall("SUM", List.of(itemsSumSubquery)),
                    false, null))
                .where(new BinaryExpression(
                    new Path(orderCustomerId, null),
                    StandardOperator.Binary.EQUALS.identifier(),
                    customerIdPath
                ))
                .build());

            var query = Query.builder()
                .from(AliasedRoot.of(customerRoot))
                .selector(new MultiExprSelector(
                    Set.of(
                        new SelectedExpression(new Path(customerName, null), "name"),
                        new SelectedExpression(ordersSubquery, "total_items_value")
                    ),
                    false
                ))
                .orderBy(OrderBy.asc(new Path(customerId, null)))
                .build();

            // Alice: order 1 items (50+50=100), order 2 items (200) -> total 300
            // Bob: order 3 items (150), order 4 items (350) -> total 500
            // Charlie: no orders -> null
            var result = dsl.fetch(transformer.transform(query));

            assertThat(result).extracting(r -> r.get("name")).containsExactly("Alice", "Bob", "Charlie");
            assertThat(result.getFirst().get("total_items_value", BigDecimal.class)).isEqualByComparingTo("300.00");
            assertThat(result.get(1).get("total_items_value", BigDecimal.class)).isEqualByComparingTo("500.00");
            assertThat(result.getLast().get("total_items_value")).isNull();
        }
    }
}
