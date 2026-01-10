package com.rorm.engine;

import com.rorm.metamodel.*;
import com.rorm.metamodel.ReferenceAttribute.InverseRootTableColumn;
import com.rorm.metamodel.ReferenceAttribute.JoinTableMapping;
import com.rorm.query.Expression.BinaryExpression;
import com.rorm.query.Expression.FunctionCall;
import com.rorm.query.Expression.Literal;
import com.rorm.query.Operator.BinaryOperator;
import com.rorm.query.*;
import com.rorm.query.Selector.MultiExprSelector;
import com.rorm.query.Selector.SingleExprSelector;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@SuppressWarnings("FieldCanBeLocal")
@DisplayName("JoinCollector")
class JoinCollectorTest {

    private static Root customerRoot;
    private static Root orderRoot;
    private static Root orderItemRoot;
    private static Root addressRoot;

    private static BasicAttribute customerId;
    private static BasicAttribute customerName;
    private static BasicAttribute orderId;
    private static BasicAttribute orderTotal;
    private static BasicAttribute orderCustomerId;
    private static BasicAttribute itemId;
    private static BasicAttribute itemPrice;
    private static BasicAttribute itemOrderId;
    private static BasicAttribute addressId;
    private static BasicAttribute addressCity;

    private static SingularReferenceAttribute orderCustomer;
    private static SingularReferenceAttribute itemOrder;
    private static SingularReferenceAttribute customerAddress;
    private static PluralReferenceAttribute customerOrders;

    private final ExpressionTransformer expr = new ExpressionTransformer();
    private final JoinCollector joinCollector = new JoinCollector(expr);

    @BeforeAll
    static void setupMetamodel() {
        // Address root
        addressId = new BasicAttribute("id", new AttributeLocation("addresses", "id"), new DataType.NumericType(19, 0));
        addressCity = new BasicAttribute("city", new AttributeLocation("addresses", "city"), new DataType.StringType());
        addressRoot = new Root("addresses", List.of(addressId, addressCity), IdDescriptor.longId("addresses"));

        // Customer root
        customerId = new BasicAttribute("id", new AttributeLocation("customers", "id"), new DataType.NumericType(19, 0));
        customerName = new BasicAttribute("name", new AttributeLocation("customers", "name"), new DataType.StringType());
        customerAddress = new SingularReferenceAttribute("address", addressRoot,
            new JoinTableMapping(new AttributeLocation("customers", "address_id"), "id"));

        // Order root
        orderId = new BasicAttribute("id", new AttributeLocation("orders", "id"), new DataType.NumericType(19, 0));
        orderTotal = new BasicAttribute("total", new AttributeLocation("orders", "total"), new DataType.NumericType(10, 2));
        orderCustomerId = new BasicAttribute("customer_id", new AttributeLocation("orders", "customer_id"), new DataType.NumericType(19, 0));

        // Temporarily create orderRoot without customer reference
        orderRoot = new Root("orders", List.of(orderId, orderTotal, orderCustomerId), IdDescriptor.longId("orders"));

        // Order item root
        itemId = new BasicAttribute("id", new AttributeLocation("order_items", "id"), new DataType.NumericType(19, 0));
        itemPrice = new BasicAttribute("price", new AttributeLocation("order_items", "price"), new DataType.NumericType(10, 2));
        itemOrderId = new BasicAttribute("order_id", new AttributeLocation("order_items", "order_id"), new DataType.NumericType(19, 0));
        itemOrder = new SingularReferenceAttribute("order", orderRoot,
            new JoinTableMapping(new AttributeLocation("order_items", "order_id"), "id"));
        orderItemRoot = new Root("order_items", List.of(itemId, itemPrice, itemOrderId, itemOrder), IdDescriptor.longId("order_items"));

        // Customer with orders reference
        customerOrders = new PluralReferenceAttribute("orders", orderRoot,
            new InverseRootTableColumn("customer_id"));
        customerRoot = new Root("customers", List.of(customerId, customerName, customerAddress, customerOrders), IdDescriptor.longId("customers"));

        // Update orderCustomer and orderRoot
        orderCustomer = new SingularReferenceAttribute("customer", customerRoot,
            new JoinTableMapping(new AttributeLocation("orders", "customer_id"), "id"));
        orderRoot = new Root("orders", List.of(orderId, orderTotal, orderCustomerId, orderCustomer), IdDescriptor.longId("orders"));
    }

    private Set<QueryContext.JoinInfo> collectJoins(Query query) {
        return expr.withContext(new QueryContext(query.from()), () ->
            joinCollector.collectFromQuery(query)
        );
    }

    @Nested
    @DisplayName("Collects joins from simple paths")
    class SimplePathJoins {

        @Test
        @DisplayName("No joins needed for root-level attributes")
        void noJoinsForRootAttributes() {
            var query = Query.builder()
                .from(customerRoot)
                .selector(new MultiExprSelector(
                    Set.of(
                        new SelectedExpression(new Path(customerId, null), "id"),
                        new SelectedExpression(new Path(customerName, null), "name")
                    ),
                    false
                ))
                .build();

            var joins = collectJoins(query);

            assertThat(joins).isEmpty();
        }

        @Test
        @DisplayName("Single join for singular reference navigation")
        void singleJoinForSingularReference() {
            // SELECT c.name, a.city FROM customers c JOIN addresses a
            var addressPath = new Path(customerAddress, null);
            var cityPath = new Path(addressCity, addressPath);

            var query = Query.builder()
                .from(customerRoot)
                .selector(new MultiExprSelector(
                    Set.of(
                        new SelectedExpression(new Path(customerName, null), "name"),
                        new SelectedExpression(cityPath, "city")
                    ),
                    false
                ))
                .build();

            var joins = collectJoins(query);

            assertThat(joins)
                .singleElement()
                .satisfies(join -> {
                    assertThat(join.actualTableName()).isEqualTo("addresses");
                    assertThat(join.leftJoinColumn()).isNotNull();
                    assertThat(join.rightJoinColumn()).isNotNull();
                });
        }

        @Test
        @DisplayName("Join for plural reference navigation")
        void joinForPluralReference() {
            // SELECT c.name, o.total FROM customers c JOIN orders o
            var ordersPath = new Path(customerOrders, null);
            var totalPath = new Path(orderTotal, ordersPath);

            var query = Query.builder()
                .from(customerRoot)
                .selector(new MultiExprSelector(
                    Set.of(
                        new SelectedExpression(new Path(customerName, null), "name"),
                        new SelectedExpression(totalPath, "total")
                    ),
                    false
                ))
                .build();

            assertThat(collectJoins(query))
                .extracting(QueryContext.JoinInfo::actualTableName)
                .containsExactly("orders");
        }

        @Test
        @DisplayName("Multiple joins for chained navigation")
        void multipleJoinsForChainedNavigation() {
            // SELECT i.price, c.name FROM order_items i JOIN orders o JOIN customers c
            var orderPath = new Path(itemOrder, null);
            var customerPath = new Path(orderCustomer, orderPath);
            var namePath = new Path(customerName, customerPath);

            var query = Query.builder()
                .from(orderItemRoot)
                .selector(new MultiExprSelector(
                    Set.of(
                        new SelectedExpression(new Path(itemPrice, null), "price"),
                        new SelectedExpression(namePath, "customer_name")
                    ),
                    false
                ))
                .build();

            assertThat(collectJoins(query))
                .extracting(QueryContext.JoinInfo::actualTableName)
                .containsExactly("orders", "customers");
        }
    }

    @Nested
    @DisplayName("Collects joins from WHERE clause")
    class WhereClauseJoins {

        @Test
        @DisplayName("Join from navigation in WHERE")
        void joinFromNavigationInWhere() {
            // SELECT name FROM customers WHERE address.city = 'NYC'
            var addressPath = new Path(customerAddress, null);
            var cityPath = new Path(addressCity, addressPath);

            var query = Query.builder()
                .from(customerRoot)
                .selector(new SingleExprSelector(new Path(customerName, null), false, "name"))
                .where(new BinaryExpression(cityPath, BinaryOperator.EQUALS, new Literal("NYC")))
                .build();

            assertThat(collectJoins(query))
                .extracting(QueryContext.JoinInfo::actualTableName)
                .containsExactly("addresses");
        }
    }

    @Nested
    @DisplayName("Does not collect joins for subqueries at wrong depth")
    class SubqueryDepthHandling {

        @Test
        @DisplayName("Ignores paths inside subqueries (depth > 0)")
        void ignoresPathsInSubqueries() {
            // SELECT name FROM customers WHERE id IN (SELECT customer_id FROM orders WHERE total > 100)
            // The orders path is at depth 1, should not be collected for outer query
            var inSubquery = new Subquery(Query.builder()
                .from(orderRoot)
                .selector(new SingleExprSelector(new Path(orderCustomerId, null), false, null))
                .where(new BinaryExpression(
                    new Path(orderTotal, null),
                    BinaryOperator.GREATER_THAN,
                    new Literal(new BigDecimal("100"))
                ))
                .build());

            var query = Query.builder()
                .from(customerRoot)
                .selector(new SingleExprSelector(new Path(customerName, null), false, "name"))
                .where(new BinaryExpression(
                    new Path(customerId, null),
                    BinaryOperator.IN,
                    inSubquery
                ))
                .build();

            var joins = collectJoins(query);

            // No joins for outer query - subquery paths are independent
            assertThat(joins).isEmpty();
        }

        @Test
        @DisplayName("Collects OuterRef joins at matching depth")
        void collectsOuterRefJoinsAtMatchingDepth() {
            // SELECT name, (SELECT COUNT(*) FROM orders WHERE orders.customer_id = customers.id)
            // The OuterRef(1, customers.id) at depth 1 should create a join in the outer context
            var customerIdPath = new Path(customerId, null);
            var outerCustomerId = new OuterRef(1, customerIdPath);

            var countSubquery = new Subquery(Query.builder()
                .from(orderRoot)
                .selector(new SingleExprSelector(
                    new FunctionCall("COUNT", List.of(new Literal("*"))),
                    false, null))
                .where(new BinaryExpression(
                    new Path(orderCustomerId, null),
                    BinaryOperator.EQUALS,
                    outerCustomerId
                ))
                .build());

            var query = Query.builder()
                .from(customerRoot)
                .selector(new MultiExprSelector(
                    Set.of(
                        new SelectedExpression(new Path(customerName, null), "name"),
                        new SelectedExpression(countSubquery, "count")
                    ),
                    false
                ))
                .build();

            var joins = collectJoins(query);

            // OuterRef references customer.id which is a root attribute - no join needed
            assertThat(joins).isEmpty();
        }

        @Test
        @DisplayName("Collects OuterRef joins when navigating through relationship")
        void collectsOuterRefJoinsWithNavigation() {
            // SELECT id FROM orders o WHERE EXISTS (
            //   SELECT 1 FROM order_items i WHERE i.order_id = o.id AND i.price > (
            //     SELECT AVG(price) FROM order_items WHERE order_id = o.id
            //   )
            // )
            // This tests OuterRef(1, orders.id) being used, which needs orders table resolved
            var orderIdPath = new Path(orderId, null);

            // Inner subquery references order.id
            var innerSubquery = new Subquery(Query.builder()
                .from(orderItemRoot)
                .selector(new SingleExprSelector(new Literal(1), false, null))
                .where(new BinaryExpression(
                    new Path(itemOrderId, null),
                    BinaryOperator.EQUALS,
                    new OuterRef(1, orderIdPath)
                ))
                .build());

            var query = Query.builder()
                .from(orderRoot)
                .selector(new SingleExprSelector(new Path(orderId, null), false, "id"))
                .where(new FunctionCall("EXISTS", List.of(innerSubquery)))
                .build();

            var joins = collectJoins(query);

            // order.id is a root attribute, no join needed
            assertThat(joins).isEmpty();
        }

        @Test
        @DisplayName("Collects joins for OuterRef with navigation path")
        void collectsJoinsForOuterRefWithNavigation() {
            // SELECT o.id FROM orders o WHERE EXISTS (
            //   SELECT 1 FROM order_items i WHERE o.customer.name = 'Alice'
            // )
            // OuterRef(1, orders.customer.name) requires customer join in outer query
            var customerPath = new Path(orderCustomer, null);
            var customerNamePath = new Path(customerName, customerPath);
            var outerCustomerName = new OuterRef(1, customerNamePath);

            var existsSubquery = new Subquery(Query.builder()
                .from(orderItemRoot)
                .selector(new SingleExprSelector(new Literal(1), false, null))
                .where(new BinaryExpression(
                    outerCustomerName,
                    BinaryOperator.EQUALS,
                    new Literal("Alice")
                ))
                .build());

            var query = Query.builder()
                .from(orderRoot)
                .selector(new SingleExprSelector(new Path(orderId, null), false, "id"))
                .where(new FunctionCall("EXISTS", List.of(existsSubquery)))
                .build();

            // Should have customer join because OuterRef navigates through orders.customer
            assertThat(collectJoins(query))
                .extracting(QueryContext.JoinInfo::actualTableName)
                .containsExactly("customers");
        }
    }

    @Nested
    @DisplayName("Handles nested subqueries correctly")
    class NestedSubqueryHandling {

        @Test
        @DisplayName("OuterRef(2) at depth 2 creates join in grandparent context")
        void outerRefDepth2CreatesJoinInGrandparent() {
            // SELECT c.name FROM customers c WHERE EXISTS (
            //   SELECT 1 FROM orders o WHERE o.customer_id = c.id AND EXISTS (
            //     SELECT 1 FROM order_items i WHERE i.order_id = o.id AND c.address.city = 'NYC'
            //   )
            // )
            // The innermost c.address.city is OuterRef(2, path) which should create join in customers context

            var customerIdPath = new Path(customerId, null);
            var addressPath = new Path(customerAddress, null);
            var cityPath = new Path(addressCity, addressPath);
            var orderIdPath = new Path(orderId, null);

            // Innermost subquery with OuterRef(2) to customer's address
            var itemsSubquery = new Subquery(Query.builder()
                .from(orderItemRoot)
                .selector(new SingleExprSelector(new Literal(1), false, null))
                .where(new BinaryExpression(
                    new BinaryExpression(
                        new Path(itemOrderId, null),
                        BinaryOperator.EQUALS,
                        new OuterRef(1, orderIdPath)  // depth=1
                    ),
                    BinaryOperator.AND,
                    new BinaryExpression(
                        new OuterRef(2, cityPath),  // depth=2, customer's address.city
                        BinaryOperator.EQUALS,
                        new Literal("NYC")
                    )
                ))
                .build());

            // Middle subquery
            var ordersSubquery = new Subquery(Query.builder()
                .from(orderRoot)
                .selector(new SingleExprSelector(new Literal(1), false, null))
                .where(new BinaryExpression(
                    new BinaryExpression(
                        new Path(orderCustomerId, null),
                        BinaryOperator.EQUALS,
                        new OuterRef(1, customerIdPath)  // depth=1
                    ),
                    BinaryOperator.AND,
                    new FunctionCall("EXISTS", List.of(itemsSubquery))
                ))
                .build());

            var query = Query.builder()
                .from(customerRoot)
                .selector(new SingleExprSelector(new Path(customerName, null), false, "name"))
                .where(new FunctionCall("EXISTS", List.of(ordersSubquery)))
                .build();

            // Should have addresses join because OuterRef(2) references customer.address.city
            assertThat(collectJoins(query))
                .extracting(QueryContext.JoinInfo::actualTableName)
                .containsExactly("addresses");
        }
    }
}
