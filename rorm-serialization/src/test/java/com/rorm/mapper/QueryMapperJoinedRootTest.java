package com.rorm.mapper;

import com.rorm.dto.ExpressionDTO.PathDTO;
import com.rorm.dto.ExpressionDTO.SubqueryDTO;
import com.rorm.dto.ExpressionDTO.LiteralDTO;
import com.rorm.dto.QueryDTO.OrderByDTO;
import com.rorm.dto.QueryDTO.SetOperationDTO;
import com.rorm.dto.SelectorDTO.MultiExprSelectorDTO;
import com.rorm.dto.SelectorDTO.SelectedExpressionDTO;
import com.rorm.dto.QueryDTO;
import com.rorm.dto.QueryDTO.JoinDTO;
import com.rorm.dto.QueryDTO.JoinedRootDTO;
import com.rorm.dto.SelectorDTO.SingleExprSelectorDTO;
import com.rorm.metamodel.*;
import com.rorm.query.Expression.BinaryExpression;
import com.rorm.query.Join;
import com.rorm.query.Join.JoinType;
import com.rorm.query.Operator.BinaryOperator;
import com.rorm.query.Path;
import com.rorm.query.Query;
import com.rorm.query.SelectedExpression;
import com.rorm.query.Selector.MultiExprSelector;
import com.rorm.query.Selector.SingleExprSelector;
import com.rorm.query.Subquery;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("QueryMapper JoinedRoot Serialization")
class QueryMapperJoinedRootTest {

    private static final QueryMapper mapper;
    private static Root customerRoot;
    private static Root orderRoot;
    private static Root productRoot;
    private static ModelSpace modelSpace;
    private static BasicAttribute customerId;
    private static BasicAttribute customerName;
    private static BasicAttribute orderId;
    private static BasicAttribute orderTotal;
    private static BasicAttribute orderCustomerId;
    private static BasicAttribute productId;
    private static BasicAttribute productName;

    static {
        mapper = Mappers.getMapper(QueryMapper.class);
        try {
            var field = QueryMapper.class.getDeclaredField("pathResolver");
            field.setAccessible(true);
            field.set(mapper, new PathResolver());
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @BeforeAll
    static void setupMetamodel() {
        customerId = new BasicAttribute("id", new AttributeLocation("customers", "id"), new DataType.NumericType(19, 0));
        customerName = new BasicAttribute("name", new AttributeLocation("customers", "name"), new DataType.StringType());
        customerRoot = new Root("customers", List.of(customerId, customerName), IdDescriptor.longId("customers"));

        orderId = new BasicAttribute("id", new AttributeLocation("orders", "id"), new DataType.NumericType(19, 0));
        orderTotal = new BasicAttribute("total", new AttributeLocation("orders", "total"), new DataType.NumericType(10, 2));
        orderCustomerId = new BasicAttribute("customer_id", new AttributeLocation("orders", "customer_id"), new DataType.NumericType(19, 0));
        orderRoot = new Root("orders", List.of(orderId, orderTotal, orderCustomerId), IdDescriptor.longId("orders"));

        productId = new BasicAttribute("id", new AttributeLocation("products", "id"), new DataType.NumericType(19, 0));
        productName = new BasicAttribute("name", new AttributeLocation("products", "name"), new DataType.StringType());
        productRoot = new Root("products", List.of(productId, productName), IdDescriptor.longId("products"));

        modelSpace = new ModelSpace(Set.of(customerRoot, orderRoot, productRoot));
    }

    private LinkedHashSet<Join> joins(Join... joins) {
        return new LinkedHashSet<>(List.of(joins));
    }

    @Nested
    @DisplayName("Query to DTO conversion")
    class QueryToDtoConversion {

        @Test
        @DisplayName("serializes explicit join with JoinedRoot")
        void serializesExplicitJoinWithJoinedRoot() {
            var joinedOrders = AliasedRoot.of(orderRoot, "o");
            var orderTotalPath = new Path(orderTotal, new Path(joinedOrders, null));
            var customerIdPath = new Path(customerId, null);

            var orderCustomerIdPath = new Path(orderCustomerId, new Path(joinedOrders, null));
            var joinCondition = new BinaryExpression(customerIdPath, BinaryOperator.EQUALS.name(), orderCustomerIdPath);

            var query = Query.builder()
                .from(AliasedRoot.of(customerRoot))
                .joins(joins(new Join(joinedOrders, JoinType.INNER, joinCondition)))
                .selector(new SingleExprSelector(orderTotalPath, false, "total"))
                .build();

            var dto = mapper.toDTO(query);

            assertThat(dto.joins())
                .isNotNull()
                .hasSize(1)
                .singleElement()
                .satisfies(joinDto -> {
                    assertThat(joinDto.joinedRoot())
                        .isNotNull()
                        .satisfies(jr -> {
                            assertThat(jr.rootName()).isEqualTo("orders");
                            assertThat(jr.alias()).isEqualTo("o");
                        });
                    assertThat(joinDto.joinType()).isEqualTo(JoinType.INNER);
                    assertThat(joinDto.onCondition()).isNotNull();
                });
        }

        @Test
        @DisplayName("serializes path with JoinedRoot as alias.attribute")
        void serializesPathWithJoinedRoot() {
            var joinedOrders = AliasedRoot.of(orderRoot, "o");
            var orderTotalPath = new Path(orderTotal, new Path(joinedOrders, null));
            var customerIdPath = new Path(customerId, null);

            var orderCustomerIdPath = new Path(orderCustomerId, new Path(joinedOrders, null));
            var joinCondition = new BinaryExpression(customerIdPath, BinaryOperator.EQUALS.name(), orderCustomerIdPath);

            var query = Query.builder()
                .from(AliasedRoot.of(customerRoot))
                .joins(joins(new Join(joinedOrders, JoinType.INNER, joinCondition)))
                .selector(new SingleExprSelector(orderTotalPath, false, null))
                .build();

            var dto = mapper.toDTO(query);

            assertThat(dto.selector())
                .isInstanceOf(SingleExprSelectorDTO.class)
                .satisfies(s -> {
                    var single = (SingleExprSelectorDTO) s;
                    assertThat(single.expression())
                        .isInstanceOf(PathDTO.class)
                        .satisfies(e -> {
                            var pathDto = (PathDTO) e;
                            assertThat(pathDto.path()).isEqualTo("o.total");
                        });
                });
        }

        @Test
        @DisplayName("serializes multiple explicit joins")
        void serializesMultipleExplicitJoins() {
            var joinedOrders = AliasedRoot.of(orderRoot, "o");
            var joinedProducts = AliasedRoot.of(productRoot, "p");

            var customerIdPath = new Path(customerId, null);
            var orderCustomerIdPath = new Path(orderCustomerId, new Path(joinedOrders, null));
            var ordersJoinCondition = new BinaryExpression(customerIdPath, BinaryOperator.EQUALS.name(), orderCustomerIdPath);

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

            var dto = mapper.toDTO(query);

            assertThat(dto.joins())
                .hasSize(2)
                .extracting(JoinDTO::joinedRoot)
                .extracting(JoinedRootDTO::rootName)
                .containsExactlyInAnyOrder("orders", "products");
        }

        @Test
        @DisplayName("serializes self-join with different aliases")
        void serializesSelfJoinWithDifferentAliases() {
            var c1 = AliasedRoot.of(customerRoot, "c1");
            var c2 = AliasedRoot.of(customerRoot, "c2");

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

            var dto = mapper.toDTO(query);

            assertThat(dto.joins())
                .hasSize(2)
                .extracting(JoinDTO::joinedRoot)
                .extracting(JoinedRootDTO::alias)
                .containsExactlyInAnyOrder("c1", "c2");
        }
    }

    @Nested
    @DisplayName("FROM alias serialization")
    class FromAliasSerialization {

        @Test
        @DisplayName("serializes fromAlias in Query to DTO")
        void serializesFromAliasInQueryToDto() {
            var aliasedCustomer = AliasedRoot.of(customerRoot, "c");
            var customerNamePath = new Path(customerName, new Path(aliasedCustomer, null));

            var query = Query.builder()
                .from(AliasedRoot.of(customerRoot, "c"))
                .selector(new SingleExprSelector(customerNamePath, false, "name"))
                .build();

            var dto = mapper.toDTO(query);

            assertThat(dto.from()).isEqualTo("customers");
            assertThat(dto.fromAlias()).isEqualTo("c");
        }

        @Test
        @DisplayName("serializes fromAlias when using default (table name)")
        void serializesDefaultFromAlias() {
            var query = Query.builder()
                .from(AliasedRoot.of(customerRoot))
                .selector(new SingleExprSelector(new Path(customerName, null), false, "name"))
                .build();

            var dto = mapper.toDTO(query);

            assertThat(dto.from()).isEqualTo("customers");
            // When using AliasedRoot.of(root) without explicit alias, it defaults to table name
            assertThat(dto.fromAlias()).isEqualTo("customers");
        }

        @Test
        @DisplayName("deserializes fromAlias from DTO")
        void deserializesFromAliasFromDto() {
            var dto = new QueryDTO(
                "customers",
                "c",
                new SingleExprSelectorDTO(new PathDTO("c.name"), false, "name"),
                null,
                null, null, null, null, null, null
            );

            var query = mapper.toEntity(dto, modelSpace);

            assertThat(query.from().root()).isEqualTo(customerRoot);
            assertThat(query.from().alias()).isEqualTo("c");
        }

        @Test
        @DisplayName("deserializes path using fromAlias")
        void deserializesPathUsingFromAlias() {
            var dto = new QueryDTO(
                "customers",
                "c",
                new SingleExprSelectorDTO(new PathDTO("c.name"), false, "name"),
                null,
                null, null, null, null, null, null
            );

            var query = mapper.toEntity(dto, modelSpace);

            assertThat(query.selector())
                .isInstanceOf(SingleExprSelector.class)
                .satisfies(s -> {
                    var single = (SingleExprSelector) s;
                    assertThat(single.expression())
                        .isInstanceOf(Path.class)
                        .satisfies(p -> {
                            var path = (Path) p;
                            assertThat(path.target()).isEqualTo(customerName);
                            assertThat(path.parent()).isNotNull();
                            assertThat(path.parent().target())
                                .isInstanceOf(AliasedRoot.class)
                                .satisfies(ar -> {
                                    var aliasedRoot = (AliasedRoot) ar;
                                    assertThat(aliasedRoot.alias()).isEqualTo("c");
                                    assertThat(aliasedRoot.root()).isEqualTo(customerRoot);
                                });
                        });
                });
        }
    }

    @Nested
    @DisplayName("DTO to Query conversion")
    class DtoToQueryConversion {

        @Test
        @DisplayName("deserializes explicit join with JoinedRoot")
        void deserializesExplicitJoinWithJoinedRoot() {
            var joinedRootDto = new JoinedRootDTO("orders", "o");
            var joinDto = new JoinDTO(joinedRootDto, JoinType.INNER,
                new com.rorm.dto.ExpressionDTO.BinaryExpressionDTO(
                    new PathDTO("id"),
                    BinaryOperator.EQUALS,
                    new PathDTO("o.customer_id")
                ));

            var joins = new LinkedHashSet<JoinDTO>();
            joins.add(joinDto);

            var dto = new QueryDTO(
                "customers",
                null,
                new SingleExprSelectorDTO(new PathDTO("o.total"), false, "total"),
                joins,
                null, null, null, null, null, null
            );

            var query = mapper.toEntity(dto, modelSpace);

            assertThat(query.joins())
                .hasSize(1)
                .singleElement()
                .satisfies(join -> {
                    assertThat(join.aliasedRoot().root()).isEqualTo(orderRoot);
                    assertThat(join.aliasedRoot().alias()).isEqualTo("o");
                    assertThat(join.joinType()).isEqualTo(JoinType.INNER);
                });
        }

        @Test
        @DisplayName("deserializes JoinedRoot with default alias when alias is null")
        void deserializesJoinedRootWithDefaultAlias() {
            var joinedRootDto = new JoinedRootDTO("orders", null);
            var joinDto = new JoinDTO(joinedRootDto, JoinType.CROSS, null);

            var joins = new LinkedHashSet<JoinDTO>();
            joins.add(joinDto);

            var dto = new QueryDTO(
                "customers",
                null,
                new SingleExprSelectorDTO(new PathDTO("name"), false, null),
                joins,
                null, null, null, null, null, null
            );

            var query = mapper.toEntity(dto, modelSpace);

            assertThat(query.joins())
                .singleElement()
                .satisfies(join -> {
                    assertThat(join.aliasedRoot().alias()).isEqualTo("orders");
                });
        }

        @Test
        @DisplayName("deserializes outerRef with alias-prefixed path inside correlated subquery")
        void deserializesOuterRefWithAliasPrefix() {
            var subquery = new QueryDTO(
                "orders",
                "o",
                new SingleExprSelectorDTO(new PathDTO("o.id"), false, null),
                null,
                new com.rorm.dto.ExpressionDTO.BinaryExpressionDTO(
                    new PathDTO("o.customer_id"),
                    BinaryOperator.EQUALS,
                    new PathDTO("c.id")
                ),
                null, null, null, null, null
            );

            var dto = new QueryDTO(
                "customers",
                "c",
                new SingleExprSelectorDTO(new SubqueryDTO(subquery), false, "x"),
                null,
                null, null, null, null, null, null
            );

            var query = mapper.toEntity(dto, modelSpace);

            assertThat(query.selector())
                .isInstanceOf(SingleExprSelector.class)
                .extracting(sel -> ((SingleExprSelector) sel).expression())
                .isInstanceOf(com.rorm.query.Subquery.class);
        }

        @Test
        @DisplayName("deserializes ORDER BY selector alias in set-operation query")
        void deserializesOrderBySelectorAliasInSetOperationQuery() {
            var leftSelector = new MultiExprSelectorDTO(new LinkedHashSet<>(Set.of(
                new SelectedExpressionDTO(new LiteralDTO("left"), "branch"),
                new SelectedExpressionDTO(new PathDTO("id"), "id")
            )), false);
            var rightSelector = new MultiExprSelectorDTO(new LinkedHashSet<>(Set.of(
                new SelectedExpressionDTO(new LiteralDTO("right"), "branch"),
                new SelectedExpressionDTO(new PathDTO("id"), "id")
            )), false);

            var rightQuery = new QueryDTO(
                "orders",
                "o2",
                rightSelector,
                null,
                null, null, null, null, null, null
            );

            var dto = new QueryDTO(
                "orders",
                "o1",
                leftSelector,
                null,
                null,
                null,
                null,
                List.of(new OrderByDTO(new PathDTO("branch"), true, null)),
                null,
                null,
                null,
                null,
                List.of(new SetOperationDTO("UNION", rightQuery))
            );

            var query = mapper.toEntity(dto, modelSpace);

            assertThat(query.orderBy())
                .singleElement()
                .extracting(ob -> ob.expression())
                .isInstanceOf(Path.class)
                .extracting(expr -> ((Path) expr).target())
                .isInstanceOf(BasicAttribute.class)
                .extracting(attr -> ((BasicAttribute) attr).name())
                .isEqualTo("branch");
        }

        @Test
        @DisplayName("maps correlated scalar subquery outerRef to parent query attribute")
        void mapsCorrelatedScalarSubqueryOuterRefToParentQueryAttribute() {
            var subquery = new QueryDTO(
                "orders",
                "o",
                new SingleExprSelectorDTO(new PathDTO("o.id"), false, null),
                null,
                new com.rorm.dto.ExpressionDTO.BinaryExpressionDTO(
                    new PathDTO("o.customer_id"),
                    BinaryOperator.EQUALS,
                    new PathDTO("c.id")
                ),
                null, null, null, null, null
            );

            var dto = new QueryDTO(
                "customers",
                "c",
                new SingleExprSelectorDTO(new SubqueryDTO(subquery), false, "order_id"),
                null,
                null, null, null, null, null, null
            );

            var query = mapper.toEntity(dto, modelSpace);

            assertThat(query.selector())
                .isInstanceOf(SingleExprSelector.class)
                .extracting(sel -> ((SingleExprSelector) sel).expression())
                .isInstanceOf(Subquery.class)
                .satisfies(subqueryExpr -> {
                    var mappedSubquery = ((Subquery) subqueryExpr).query();
                    assertThat(mappedSubquery.where())
                        .isInstanceOf(BinaryExpression.class)
                        .extracting(where -> ((BinaryExpression) where).right())
                        .isInstanceOf(Path.class)
                        .extracting(or -> ((Path) or).target())
                        .isEqualTo(customerId);
                });
        }
    }

    @Nested
    @DisplayName("CTE context root resolution")
    class CteContextRootResolution {

        @Test
        @DisplayName("deserializes FROM root from CTE name")
        void deserializesFromRootFromCteName() {
            var cteQuery = new QueryDTO(
                "orders",
                null,
                new SingleExprSelectorDTO(new PathDTO("id"), false, "id"),
                null,
                null, null, null, null, null, null
            );
            var cte = new QueryDTO.CteDTO("order_ids", cteQuery, List.of("id"));

            var dto = new QueryDTO(
                "order_ids",
                "oi",
                new SingleExprSelectorDTO(new PathDTO("id"), false, "id"),
                null,
                null, null, null, null, null, null,
                List.of(cte),
                null
            );

            var query = mapper.toEntity(dto, modelSpace);

            assertThat(query.from().root().primaryTableName()).isEqualTo("order_ids");
            assertThat(query.from().alias()).isEqualTo("oi");
            assertThat(query.selector())
                .isInstanceOf(SingleExprSelector.class)
                .extracting(sel -> ((SingleExprSelector) sel).expression())
                .isInstanceOf(Path.class)
                .extracting(expr -> ((Path) expr).target())
                .isInstanceOf(BasicAttribute.class)
                .extracting(attr -> ((BasicAttribute) attr).name())
                .isEqualTo("id");
        }

        @Test
        @DisplayName("deserializes JOIN root from CTE name and resolves aliased CTE path")
        void deserializesJoinRootFromCteName() {
            var cteQuery = new QueryDTO(
                "orders",
                null,
                new SingleExprSelectorDTO(new PathDTO("id"), false, "id"),
                null,
                null, null, null, null, null, null
            );
            var cte = new QueryDTO.CteDTO("order_ids", cteQuery, List.of("id"));

            var join = new JoinDTO(
                new JoinedRootDTO("order_ids", "oi"),
                JoinType.INNER,
                new com.rorm.dto.ExpressionDTO.BinaryExpressionDTO(
                    new PathDTO("id"),
                    BinaryOperator.EQUALS,
                    new PathDTO("oi.id")
                )
            );

            var dto = new QueryDTO(
                "customers",
                null,
                new SingleExprSelectorDTO(new PathDTO("oi.id"), false, "id"),
                new LinkedHashSet<>(List.of(join)),
                null, null, null, null, null, null,
                List.of(cte),
                null
            );

            var query = mapper.toEntity(dto, modelSpace);

            assertThat(query.joins())
                .singleElement()
                .satisfies(j -> {
                    assertThat(j.aliasedRoot().root().primaryTableName()).isEqualTo("order_ids");
                    assertThat(j.aliasedRoot().alias()).isEqualTo("oi");
                });
            assertThat(query.selector())
                .isInstanceOf(SingleExprSelector.class)
                .extracting(sel -> ((SingleExprSelector) sel).expression())
                .isInstanceOf(Path.class)
                .satisfies(expr -> {
                    var path = (Path) expr;
                    assertThat(path.target()).isInstanceOf(BasicAttribute.class);
                    assertThat(((BasicAttribute) path.target()).name()).isEqualTo("id");
                    assertThat(path.parent()).isNotNull();
                    assertThat(path.parent().target()).isInstanceOf(AliasedRoot.class);
                });
        }
    }
}
