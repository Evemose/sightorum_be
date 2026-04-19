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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@SuppressWarnings("FieldCanBeLocal")
@DisplayName("ScopeResolver")
class ScopeResolverTest {

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

    @BeforeAll
    static void setupMetamodel() {
        addressId = new BasicAttribute("id", new AttributeLocation("addresses", "id"), new DataType.NumericType(19, 0));
        addressCity = new BasicAttribute("city", new AttributeLocation("addresses", "city"), new DataType.StringType());
        addressRoot = new Root("addresses", List.of(addressId, addressCity), IdDescriptor.longId("addresses"));

        customerId = new BasicAttribute("id", new AttributeLocation("customers", "id"), new DataType.NumericType(19, 0));
        customerName = new BasicAttribute("name", new AttributeLocation("customers", "name"), new DataType.StringType());
        customerAddress = new SingularReferenceAttribute("address", addressRoot,
            new JoinTableMapping(new AttributeLocation("customers", "address_id"), "id"));

        orderId = new BasicAttribute("id", new AttributeLocation("orders", "id"), new DataType.NumericType(19, 0));
        orderTotal = new BasicAttribute("total", new AttributeLocation("orders", "total"), new DataType.NumericType(10, 2));
        orderCustomerId = new BasicAttribute("customer_id", new AttributeLocation("orders", "customer_id"), new DataType.NumericType(19, 0));

        orderRoot = new Root("orders", List.of(orderId, orderTotal, orderCustomerId), IdDescriptor.longId("orders"));

        itemId = new BasicAttribute("id", new AttributeLocation("order_items", "id"), new DataType.NumericType(19, 0));
        itemPrice = new BasicAttribute("price", new AttributeLocation("order_items", "price"), new DataType.NumericType(10, 2));
        itemOrderId = new BasicAttribute("order_id", new AttributeLocation("order_items", "order_id"), new DataType.NumericType(19, 0));
        itemOrder = new SingularReferenceAttribute("order", orderRoot,
            new JoinTableMapping(new AttributeLocation("order_items", "order_id"), "id"));
        orderItemRoot = new Root("order_items", List.of(itemId, itemPrice, itemOrderId, itemOrder), IdDescriptor.longId("order_items"));

        customerOrders = new PluralReferenceAttribute("orders", orderRoot,
            new InverseRootTableColumn("customer_id"));
        customerRoot = new Root("customers", List.of(customerId, customerName, customerAddress, customerOrders), IdDescriptor.longId("customers"));

        orderCustomer = new SingularReferenceAttribute("customer", customerRoot,
            new JoinTableMapping(new AttributeLocation("orders", "customer_id"), "id"));
        orderRoot = new Root("orders", List.of(orderId, orderTotal, orderCustomerId, orderCustomer), IdDescriptor.longId("orders"));
    }

    private Set<QueryContext.JoinInfo> resolveJoins(Query query) {
        return ScopeResolver.resolveQueryJoins(query, new QueryContext(query.from()));
    }

    @Nested
    @DisplayName("Collects joins from simple paths")
    class SimplePathJoins {

        @Test
        @DisplayName("No joins needed for root-level attributes")
        void noJoinsForRootAttributes() {
            var query = Query.builder()
                .from(AliasedRoot.of(customerRoot))
                .selector(new MultiExprSelector(
                    Set.of(
                        new SelectedExpression(new Path(customerId, null), "id"),
                        new SelectedExpression(new Path(customerName, null), "name")
                    ),
                    false
                ))
                .build();

            assertThat(resolveJoins(query)).isEmpty();
        }

        @Test
        @DisplayName("Single join for singular reference navigation")
        void singleJoinForSingularReference() {
            var addressPath = new Path(customerAddress, null);
            var cityPath = new Path(addressCity, addressPath);

            var query = Query.builder()
                .from(AliasedRoot.of(customerRoot))
                .selector(new MultiExprSelector(
                    Set.of(
                        new SelectedExpression(new Path(customerName, null), "name"),
                        new SelectedExpression(cityPath, "city")
                    ),
                    false
                ))
                .build();

            assertThat(resolveJoins(query))
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
            var ordersPath = new Path(customerOrders, null);
            var totalPath = new Path(orderTotal, ordersPath);

            var query = Query.builder()
                .from(AliasedRoot.of(customerRoot))
                .selector(new MultiExprSelector(
                    Set.of(
                        new SelectedExpression(new Path(customerName, null), "name"),
                        new SelectedExpression(totalPath, "total")
                    ),
                    false
                ))
                .build();

            assertThat(resolveJoins(query))
                .extracting(QueryContext.JoinInfo::actualTableName)
                .containsExactly("orders");
        }

        @Test
        @DisplayName("Multiple joins for chained navigation")
        void multipleJoinsForChainedNavigation() {
            var orderPath = new Path(itemOrder, null);
            var customerPath = new Path(orderCustomer, orderPath);
            var namePath = new Path(customerName, customerPath);

            var query = Query.builder()
                .from(AliasedRoot.of(orderItemRoot))
                .selector(new MultiExprSelector(
                    Set.of(
                        new SelectedExpression(new Path(itemPrice, null), "price"),
                        new SelectedExpression(namePath, "customer_name")
                    ),
                    false
                ))
                .build();

            assertThat(resolveJoins(query))
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
            var addressPath = new Path(customerAddress, null);
            var cityPath = new Path(addressCity, addressPath);

            var query = Query.builder()
                .from(AliasedRoot.of(customerRoot))
                .selector(new SingleExprSelector(new Path(customerName, null), false, "name"))
                .where(new BinaryExpression(cityPath, StandardOperator.Binary.EQUALS.identifier(), new Literal("NYC")))
                .build();

            assertThat(resolveJoins(query))
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
                .selector(new SingleExprSelector(new Path(customerName, null), false, "name"))
                .where(new BinaryExpression(
                    new Path(customerId, null),
                    StandardOperator.Binary.IN.identifier(),
                    inSubquery
                ))
                .build();

            assertThat(resolveJoins(query)).isEmpty();
        }

        @Test
        @DisplayName("Collects OuterRef joins at matching depth")
        void collectsOuterRefJoinsAtMatchingDepth() {
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
                        new SelectedExpression(countSubquery, "count")
                    ),
                    false
                ))
                .build();

            assertThat(resolveJoins(query)).isEmpty();
        }

        @Test
        @DisplayName("Collects OuterRef joins when navigating through relationship")
        void collectsOuterRefJoinsWithNavigation() {
            var orderIdPath = new Path(orderId, null);

            var innerSubquery = new Subquery(Query.builder()
                .from(AliasedRoot.of(orderItemRoot))
                .selector(new SingleExprSelector(new Literal(1), false, null))
                .where(new BinaryExpression(
                    new Path(itemOrderId, null),
                    StandardOperator.Binary.EQUALS.identifier(),
                    orderIdPath
                ))
                .build());

            var query = Query.builder()
                .from(AliasedRoot.of(orderRoot))
                .selector(new SingleExprSelector(new Path(orderId, null), false, "id"))
                .where(new FunctionCall("EXISTS", List.of(innerSubquery)))
                .build();

            assertThat(resolveJoins(query)).isEmpty();
        }

        @Test
        @DisplayName("Collects joins for OuterRef with navigation path")
        void collectsJoinsForOuterRefWithNavigation() {
            var customerPath = new Path(orderCustomer, null);
            var customerNamePath = new Path(customerName, customerPath);
            var outerCustomerName = customerNamePath;

            var existsSubquery = new Subquery(Query.builder()
                .from(AliasedRoot.of(orderItemRoot))
                .selector(new SingleExprSelector(new Literal(1), false, null))
                .where(new BinaryExpression(
                    outerCustomerName,
                    StandardOperator.Binary.EQUALS.identifier(),
                    new Literal("Alice")
                ))
                .build());

            var query = Query.builder()
                .from(AliasedRoot.of(orderRoot))
                .selector(new SingleExprSelector(new Path(orderId, null), false, "id"))
                .where(new FunctionCall("EXISTS", List.of(existsSubquery)))
                .build();

            assertThat(resolveJoins(query))
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
            var customerIdPath = new Path(customerId, null);
            var addressPath = new Path(customerAddress, null);
            var cityPath = new Path(addressCity, addressPath);
            var orderIdPath = new Path(orderId, null);

            var itemsSubquery = new Subquery(Query.builder()
                .from(AliasedRoot.of(orderItemRoot))
                .selector(new SingleExprSelector(new Literal(1), false, null))
                .where(new BinaryExpression(
                    new BinaryExpression(
                        new Path(itemOrderId, null),
                        StandardOperator.Binary.EQUALS.identifier(),
                        orderIdPath
                    ),
                    StandardOperator.Binary.AND.identifier(),
                    new BinaryExpression(
                        cityPath,
                        StandardOperator.Binary.EQUALS.identifier(),
                        new Literal("NYC")
                    )
                ))
                .build());

            var ordersSubquery = new Subquery(Query.builder()
                .from(AliasedRoot.of(orderRoot))
                .selector(new SingleExprSelector(new Literal(1), false, null))
                .where(new BinaryExpression(
                    new BinaryExpression(
                        new Path(orderCustomerId, null),
                        StandardOperator.Binary.EQUALS.identifier(),
                        customerIdPath
                    ),
                    StandardOperator.Binary.AND.identifier(),
                    new FunctionCall("EXISTS", List.of(itemsSubquery))
                ))
                .build());

            var query = Query.builder()
                .from(AliasedRoot.of(customerRoot))
                .selector(new SingleExprSelector(new Path(customerName, null), false, "name"))
                .where(new FunctionCall("EXISTS", List.of(ordersSubquery)))
                .build();

            assertThat(resolveJoins(query))
                .extracting(QueryContext.JoinInfo::actualTableName)
                .containsExactly("addresses");
        }
    }
}
