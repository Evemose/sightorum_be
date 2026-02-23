package com.rorm.engine;

import com.rorm.metamodel.*;
import com.rorm.query.Path;
import com.rorm.query.Query;
import com.rorm.query.Selector.SingleExprSelector;
import com.rorm.testutil.TestHandlerRegistry;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

@DisplayName("QueryContext Alias Registration")
class QueryContextAliasTest {

    private static Root customerRoot;
    private static Root orderRoot;
    private static Root productRoot;
    private static BasicAttribute customerName;
    private static SingularReferenceAttribute orderCustomer;

    @BeforeAll
    static void setupMetamodel() {
        var customerId = new BasicAttribute("id", new AttributeLocation("customers", "id"), new DataType.NumericType(19, 0));
        customerName = new BasicAttribute("name", new AttributeLocation("customers", "name"), new DataType.StringType());
        customerRoot = new Root("customers", List.of(customerId, customerName), IdDescriptor.longId("customers"));

        var orderId = new BasicAttribute("id", new AttributeLocation("orders", "id"), new DataType.NumericType(19, 0));
        var orderTotal = new BasicAttribute("total", new AttributeLocation("orders", "total"), new DataType.NumericType(10, 2));
        orderCustomer = new SingularReferenceAttribute("customer", customerRoot,
            new ReferenceAttribute.SameTableColumn("customer_id"));
        orderRoot = new Root("orders", List.of(orderId, orderTotal, orderCustomer), IdDescriptor.longId("orders"));

        var productId = new BasicAttribute("id", new AttributeLocation("products", "id"), new DataType.NumericType(19, 0));
        var productName = new BasicAttribute("name", new AttributeLocation("products", "name"), new DataType.StringType());
        productRoot = new Root("products", List.of(productId, productName), IdDescriptor.longId("products"));
    }

    @Nested
    @DisplayName("Alias registration")
    class AliasRegistration {

        @Test
        @DisplayName("registers JoinedRoot and returns JoinedRootInfo with table")
        void registersJoinedRootAndReturnsInfo() {
            var ctx = new QueryContext(customerRoot);
            var joinedOrders = AliasedRoot.of(orderRoot, "o");

            var info = ctx.registerJoinedRoot(joinedOrders);

            assertThat(info)
                .satisfies(i -> {
                    assertThat(i.aliasedRoot()).isSameAs(joinedOrders);
                    assertThat(i.table()).isNotNull();
                    assertThat(i.table().getName()).startsWith("t0_");
                });
        }

        @Test
        @DisplayName("registers multiple JoinedRoots with different aliases")
        void registersMultipleJoinedRootsWithDifferentAliases() {
            var ctx = new QueryContext(customerRoot);
            var joinedOrders = AliasedRoot.of(orderRoot, "o");
            var joinedProducts = AliasedRoot.of(productRoot, "p");

            var orderInfo = ctx.registerJoinedRoot(joinedOrders);
            var productInfo = ctx.registerJoinedRoot(joinedProducts);

            assertThat(orderInfo.aliasedRoot().alias()).isEqualTo("o");
            assertThat(productInfo.aliasedRoot().alias()).isEqualTo("p");
        }

        @Test
        @DisplayName("generates unique table aliases for each registration")
        void generatesUniqueTableAliases() {
            var ctx = new QueryContext(customerRoot);
            var o1 = AliasedRoot.of(orderRoot, "o1");
            var o2 = AliasedRoot.of(orderRoot, "o2");

            var info1 = ctx.registerJoinedRoot(o1);
            var info2 = ctx.registerJoinedRoot(o2);

            assertThat(info1.table().getName())
                .isNotEqualTo(info2.table().getName());
        }
    }

    @Nested
    @DisplayName("Duplicate alias detection")
    class DuplicateAliasDetection {

        @Test
        @DisplayName("throws DuplicateAliasException when registering same alias for different roots")
        void throwsOnDuplicateAliasForDifferentRoots() {
            var ctx = new QueryContext(customerRoot);
            var joinedOrders = AliasedRoot.of(orderRoot, "alias");
            var joinedProducts = AliasedRoot.of(productRoot, "alias");

            ctx.registerJoinedRoot(joinedOrders);

            assertThatThrownBy(() -> ctx.registerJoinedRoot(joinedProducts))
                .isInstanceOf(QueryContext.DuplicateAliasException.class)
                .satisfies(ex -> {
                    var dupEx = (QueryContext.DuplicateAliasException) ex;
                    assertThat(dupEx.getAlias()).isEqualTo("alias");
                    assertThat(dupEx.getExistingRootTable()).isEqualTo("orders");
                    assertThat(dupEx.getNewRootTable()).isEqualTo("products");
                })
                .hasMessageContaining("Duplicate alias 'alias'")
                .hasMessageContaining("orders")
                .hasMessageContaining("products");
        }

        @Test
        @DisplayName("throws DuplicateAliasException when registering same alias for same root twice")
        void throwsOnDuplicateAliasForSameRoot() {
            var ctx = new QueryContext(customerRoot);
            var joinedOrders1 = AliasedRoot.of(orderRoot, "o");
            var joinedOrders2 = AliasedRoot.of(orderRoot, "o");

            ctx.registerJoinedRoot(joinedOrders1);

            assertThatThrownBy(() -> ctx.registerJoinedRoot(joinedOrders2))
                .isInstanceOf(QueryContext.DuplicateAliasException.class)
                .satisfies(ex -> {
                    var dupEx = (QueryContext.DuplicateAliasException) ex;
                    assertThat(dupEx.getAlias()).isEqualTo("o");
                    assertThat(dupEx.getExistingRootTable()).isEqualTo("orders");
                    assertThat(dupEx.getNewRootTable()).isEqualTo("orders");
                });
        }
    }

    @Nested
    @DisplayName("getOrRegisterJoinedRoot")
    class GetOrRegisterJoinedRoot {

        @Test
        @DisplayName("registers new JoinedRoot when not already registered")
        void registersNewJoinedRoot() {
            var ctx = new QueryContext(customerRoot);
            var joinedOrders = AliasedRoot.of(orderRoot, "o");

            var info = ctx.getOrRegisterJoinedRoot(joinedOrders);

            assertThat(info)
                .satisfies(i -> {
                    assertThat(i.aliasedRoot()).isSameAs(joinedOrders);
                    assertThat(i.table()).isNotNull();
                });
        }

        @Test
        @DisplayName("returns existing info when same JoinedRoot is already registered")
        void returnsExistingInfoForSameJoinedRoot() {
            var ctx = new QueryContext(customerRoot);
            var joinedOrders1 = AliasedRoot.of(orderRoot, "o");
            var joinedOrders2 = AliasedRoot.of(orderRoot, "o");

            var info1 = ctx.getOrRegisterJoinedRoot(joinedOrders1);
            var info2 = ctx.getOrRegisterJoinedRoot(joinedOrders2);

            assertThat(info1).isSameAs(info2);
        }

        @Test
        @DisplayName("throws when alias already used for different root")
        void throwsWhenAliasUsedForDifferentRoot() {
            var ctx = new QueryContext(customerRoot);
            var joinedOrders = AliasedRoot.of(orderRoot, "x");
            var joinedProducts = AliasedRoot.of(productRoot, "x");

            ctx.getOrRegisterJoinedRoot(joinedOrders);

            assertThatThrownBy(() -> ctx.getOrRegisterJoinedRoot(joinedProducts))
                .isInstanceOf(QueryContext.DuplicateAliasException.class)
                .hasMessageContaining("Duplicate alias 'x'");
        }
    }

    @Nested
    @DisplayName("Nested context behavior")
    class NestedContextBehavior {

        @Test
        @DisplayName("nested context has independent alias registry")
        void nestedContextHasIndependentAliasRegistry() {
            var ctx = new QueryContext(customerRoot);
            var nestedCtx = ctx.nested(orderRoot);

            var joinedProducts = AliasedRoot.of(productRoot, "p");

            // Register in parent
            ctx.registerJoinedRoot(joinedProducts);

            // Same alias in nested should not conflict since registries are independent
            var nestedJoined = AliasedRoot.of(productRoot, "p");
            assertThatCode(() -> nestedCtx.registerJoinedRoot(nestedJoined))
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("nested context generates aliases with correct depth prefix")
        void nestedContextGeneratesAliasesWithCorrectDepth() {
            var ctx = new QueryContext(customerRoot);
            var nestedCtx = ctx.nested(orderRoot);
            var deepNestedCtx = nestedCtx.nested(productRoot);

            var parentJoined = AliasedRoot.of(productRoot, "p1");
            var nestedJoined = AliasedRoot.of(productRoot, "p2");
            var deepJoined = AliasedRoot.of(productRoot, "p3");

            var parentInfo = ctx.registerJoinedRoot(parentJoined);
            var nestedInfo = nestedCtx.registerJoinedRoot(nestedJoined);
            var deepInfo = deepNestedCtx.registerJoinedRoot(deepJoined);

            assertThat(parentInfo.table().getName())
                .startsWith("t0_");
            assertThat(nestedInfo.table().getName())
                .startsWith("t1_");
            assertThat(deepInfo.table().getName())
                .startsWith("t2_");
        }
    }

    @Nested
    @DisplayName("Schema qualification")
    class SchemaQualification {

        @Test
        @DisplayName("qualifies auto-joined reference tables when schema is provided")
        void qualifiesAutoJoinedReferenceTablesWhenSchemaProvided() {
            var handlerRegistry = TestHandlerRegistry.createWithAllBuiltIns();
            var expressionTransformer = new ExpressionTransformer(handlerRegistry);
            var transformer = new QueryTransformer(DSL.using(SQLDialect.POSTGRES), expressionTransformer, new JoinCollector(expressionTransformer));

            var customerNameViaReference = new Path(customerName, new Path(orderCustomer, null));
            var query = Query.builder()
                .from(AliasedRoot.of(orderRoot))
                .selector(new SingleExprSelector(customerNameViaReference, false, "customer_name"))
                .build();

            var sql = transformer.transform(query, "works").getSQL().toLowerCase();

            assertThat(sql)
                .contains("\"works\".\"orders\"")
                .contains("\"works\".\"customers\"");
        }
    }
}
