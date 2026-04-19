package com.rorm.engine;

import com.rorm.metamodel.*;
import com.rorm.metamodel.ReferenceAttribute.SameTableColumn;
import com.rorm.query.*;
import com.rorm.query.Expression.*;
import com.rorm.query.Join.JoinType;
import com.rorm.query.Selector.SingleExprSelector;
import com.rorm.testutil.TestHandlerRegistry;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("LATERAL JOIN SQL generation")
class LateralJoinTest {

    private static Root orderRoot;
    private static Root itemRoot;
    private static Root customerRoot;
    private static BasicAttribute orderId;
    private static BasicAttribute orderCustomerId;
    private static SingularReferenceAttribute orderCustomer;
    private static BasicAttribute customerId;
    private static BasicAttribute customerName;
    private static BasicAttribute itemId;
    private static BasicAttribute itemOrderId;
    private static SingularReferenceAttribute itemCustomer;

    private QueryTransformer queryTransformer;

    @BeforeAll
    static void setupMetamodel() {
        customerId = new BasicAttribute("id", new AttributeLocation("customers", "id"), new DataType.NumericType(19, 0));
        customerName = new BasicAttribute("name", new AttributeLocation("customers", "name"), new DataType.StringType());
        customerRoot = new Root("customers", List.of(customerId, customerName), IdDescriptor.longId("customers"));

        orderId = new BasicAttribute("id", new AttributeLocation("orders", "id"), new DataType.NumericType(19, 0));
        orderCustomerId = new BasicAttribute("customer_id", new AttributeLocation("orders", "customer_id"), new DataType.NumericType(19, 0));
        orderCustomer = new SingularReferenceAttribute("customer", customerRoot, new SameTableColumn("customer_id"));
        orderRoot = new Root("orders", List.of(orderId, orderCustomerId, orderCustomer), IdDescriptor.longId("orders"));

        itemId = new BasicAttribute("id", new AttributeLocation("items", "id"), new DataType.NumericType(19, 0));
        itemOrderId = new BasicAttribute("order_id", new AttributeLocation("items", "order_id"), new DataType.NumericType(19, 0));
        itemCustomer = new SingularReferenceAttribute("customer", customerRoot, new SameTableColumn("order_id"));
        itemRoot = new Root("items", List.of(itemId, itemOrderId, itemCustomer), IdDescriptor.longId("items"));
    }

    @BeforeEach
    void setUp() {
        var dslContext = DSL.using(SQLDialect.POSTGRES);
        var handlerRegistry = TestHandlerRegistry.createWithAllBuiltIns();
        var expressionTransformer = new ExpressionTransformer(handlerRegistry);
        queryTransformer = new QueryTransformer(dslContext, expressionTransformer);
    }

    @Test
    @DisplayName("CROSS JOIN LATERAL produces lateral cross join SQL")
    void crossLateralJoin() {
        var aliasedItems = AliasedRoot.of(itemRoot, "i");

        var query = Query.builder()
            .from(AliasedRoot.of(orderRoot))
            .joins(new LinkedHashSet<>(List.of(
                new Join(aliasedItems, JoinType.CROSS_LATERAL, null)
            )))
            .selector(new SingleExprSelector(new Path(orderId, null), false, "id"))
            .build();

        var sql = queryTransformer.transform(query).getSQL();

        assertThat(sql)
            .containsIgnoringCase("cross join")
            .containsIgnoringCase("lateral");
    }

    @Test
    @DisplayName("LEFT JOIN LATERAL produces lateral left join SQL")
    void leftLateralJoin() {
        var aliasedItems = AliasedRoot.of(itemRoot, "i");
        var onCondition = BinaryExpression.eq(
            new Path(orderId, null),
            new Path(itemOrderId, new Path(aliasedItems, null))
        );

        var query = Query.builder()
            .from(AliasedRoot.of(orderRoot))
            .joins(new LinkedHashSet<>(List.of(
                new Join(aliasedItems, JoinType.LEFT_LATERAL, onCondition)
            )))
            .selector(new SingleExprSelector(new Path(orderId, null), false, "id"))
            .build();

        var sql = queryTransformer.transform(query).getSQL();

        assertThat(sql)
            .containsIgnoringCase("left")
            .containsIgnoringCase("join")
            .containsIgnoringCase("lateral");
    }

    @Test
    @DisplayName("CROSS_LATERAL join does not require ON condition")
    void crossLateralNoOnCondition() {
        var aliasedItems = AliasedRoot.of(itemRoot, "i");

        // Should not throw — CROSS_LATERAL doesn't require ON condition
        var join = new Join(aliasedItems, JoinType.CROSS_LATERAL, null);
        assertThat(join.joinType()).isEqualTo(JoinType.CROSS_LATERAL);
    }

    @Test
    @DisplayName("LEFT_LATERAL join requires ON condition")
    void leftLateralRequiresOnCondition() {
        var aliasedItems = AliasedRoot.of(itemRoot, "i");

        assertThatThrownBy(() -> new Join(aliasedItems, JoinType.LEFT_LATERAL, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("ON condition is required");
    }

    @Test
    @DisplayName("auto path joins are emitted before LATERAL joins")
    void autoJoinsBeforeLateralJoin() {
        var aliasedItems = AliasedRoot.of(itemRoot, "i");
        var customerNamePath = new Path(customerName, new Path(orderCustomer, null));

        var query = Query.builder()
            .from(AliasedRoot.of(orderRoot))
            .joins(new LinkedHashSet<>(List.of(
                new Join(aliasedItems, JoinType.CROSS_LATERAL, null)
            )))
            .selector(new SingleExprSelector(customerNamePath, false, "customer_name"))
            .build();

        var sql = queryTransformer.transform(query).getSQL().toLowerCase();

        assertThat(sql).containsIgnoringCase("lateral");
        assertThat(sql.indexOf("join \"customers\""))
            .isGreaterThanOrEqualTo(0)
            .isLessThan(sql.indexOf("lateral"));
    }

    @Test
    @DisplayName("LEFT JOIN ON reference-id path avoids duplicate implicit join")
    void leftJoinOnReferenceIdPathAvoidsDuplicateImplicitJoin() {
        var joinedCustomer = AliasedRoot.of(customerRoot, "c");
        var leftSideReferenceId = new Path(customerId, new Path(orderCustomer, null));
        var rightSideJoinedId = new Path(customerId, new Path(joinedCustomer, null));
        var onCondition = BinaryExpression.eq(leftSideReferenceId, rightSideJoinedId);

        var query = Query.builder()
            .from(AliasedRoot.of(orderRoot))
            .joins(new LinkedHashSet<>(List.of(
                new Join(joinedCustomer, JoinType.LEFT, onCondition)
            )))
            .selector(new SingleExprSelector(new Path(orderId, null), false, "id"))
            .build();

        var sql = queryTransformer.transform(query).getSQL();
        var normalized = sql.toLowerCase();

        assertThat(normalized).contains("left outer join");
        assertThat(countOccurrences(normalized, "join \"customers\"")).isEqualTo(1);
    }

    private int countOccurrences(String text, String token) {
        var count = 0;
        var fromIndex = 0;
        while (true) {
            var idx = text.indexOf(token, fromIndex);
            if (idx < 0) {
                return count;
            }
            count++;
            fromIndex = idx + token.length();
        }
    }

    @Test
    @DisplayName("auto joins that depend on lateral alias are emitted after lateral join")
    void dependentAutoJoinsAfterLateralJoin() {
        var aliasedItems = AliasedRoot.of(itemRoot, "i");
        var customerNameViaLateral = new Path(customerName, new Path(itemCustomer, new Path(aliasedItems, null)));

        var query = Query.builder()
            .from(AliasedRoot.of(orderRoot))
            .joins(new LinkedHashSet<>(List.of(
                new Join(aliasedItems, JoinType.CROSS_LATERAL, null)
            )))
            .selector(new SingleExprSelector(customerNameViaLateral, false, "customer_name"))
            .build();

        var sql = queryTransformer.transform(query).getSQL().toLowerCase();
        var lateralPos = sql.indexOf("lateral");
        var dependentJoinPos = sql.lastIndexOf("join \"customers\"");

        assertThat(lateralPos).isGreaterThanOrEqualTo(0);
        assertThat(dependentJoinPos).isGreaterThan(lateralPos);
    }
}
