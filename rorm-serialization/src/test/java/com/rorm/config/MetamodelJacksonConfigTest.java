package com.rorm.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rorm.metamodel.*;
import com.rorm.metamodel.CollectionAttribute.BasicElement;
import com.rorm.metamodel.CollectionAttribute.CompositeElement;
import com.rorm.metamodel.ReferenceAttribute.InverseRootTableColumn;
import com.rorm.metamodel.ReferenceAttribute.JoinTableMapping;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@JsonTest
@Import(MetamodelJacksonConfig.class)
@DisplayName("MetamodelJacksonConfig")
class MetamodelJacksonConfigTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Nested
    @DisplayName("Serialization includes type info")
    class TypeInfoSerialization {

        @Test
        @DisplayName("BasicAttribute includes @type")
        void basicAttributeIncludesType() throws Exception {
            var attr = new BasicAttribute("id", new AttributeLocation("users", "id"), new DataType.NumericType(19, 0));

            var json = objectMapper.writeValueAsString(attr);

            assertThat(json).contains("\"@type\":\"basic\"");
        }

        @Test
        @DisplayName("SingularReferenceAttribute includes @type")
        void singularReferenceAttributeIncludesType() throws Exception {
            var targetRoot = new Root("addresses", List.of(), IdDescriptor.longId("addresses"));
            var attr = new SingularReferenceAttribute("address", targetRoot,
                new JoinTableMapping(new AttributeLocation("users", "address_id"), "id"));

            var json = objectMapper.writeValueAsString(attr);

            assertThat(json)
                .contains("\"@type\":\"singularRef\"")
                .contains("\"@type\":\"joinTable\"");
        }

        @Test
        @DisplayName("PluralReferenceAttribute includes @type")
        void pluralReferenceAttributeIncludesType() throws Exception {
            var targetRoot = new Root("orders", List.of(), IdDescriptor.longId("orders"));
            var attr = new PluralReferenceAttribute("orders", targetRoot,
                new InverseRootTableColumn("customer_id"));

            var json = objectMapper.writeValueAsString(attr);

            assertThat(json)
                .contains("\"@type\":\"pluralRef\"")
                .contains("\"@type\":\"inverseColumn\"");
        }

        @Test
        @DisplayName("CollectionAttribute with BasicElement includes @type")
        void collectionAttributeWithBasicElementIncludesType() throws Exception {
            var attr = new CollectionAttribute("tags", "user_tags",
                new BasicElement(new AttributeLocation("user_tags", "tag"), new DataType.StringType()));

            var json = objectMapper.writeValueAsString(attr);

            assertThat(json)
                .contains("\"@type\":\"collection\"")
                .contains("\"@type\":\"basicElement\"");
        }

        @Test
        @DisplayName("CompositeAttribute includes @type")
        void compositeAttributeIncludesType() throws Exception {
            var street = new BasicAttribute("street", new AttributeLocation("users", "street"), new DataType.StringType());
            var city = new BasicAttribute("city", new AttributeLocation("users", "city"), new DataType.StringType());
            var attr = new CompositeAttribute("address", Set.of(street, city));

            var json = objectMapper.writeValueAsString(attr);

            assertThat(json).contains("\"@type\":\"composite\"");
        }
    }

    @Nested
    @DisplayName("Round-trip serialization")
    class RoundTripSerialization {

        @Test
        @DisplayName("BasicAttribute round-trips correctly")
        void basicAttributeRoundTrips() throws Exception {
            var original = new BasicAttribute("id", new AttributeLocation("users", "id"), new DataType.NumericType(19, 0));

            var json = objectMapper.writeValueAsString(original);
            var deserialized = objectMapper.readValue(json, BasicAttribute.class);

            assertThat(deserialized).isEqualTo(original);
        }

        @Test
        @DisplayName("Root with basic attributes round-trips correctly")
        void rootWithBasicAttributesRoundTrips() throws Exception {
            var id = new BasicAttribute("id", new AttributeLocation("users", "id"), new DataType.NumericType(19, 0));
            var name = new BasicAttribute("name", new AttributeLocation("users", "name"), new DataType.StringType());
            var original = new Root("users", List.of(id, name), IdDescriptor.longId("users"));

            var json = objectMapper.writeValueAsString(original);
            var deserialized = objectMapper.readValue(json, Root.class);

            assertThat(deserialized.primaryTableName()).isEqualTo(original.primaryTableName());
            assertThat(deserialized.attributes()).hasSize(2);
            assertThat(deserialized.attributes())
                .extracting(Attribute::name)
                .containsExactly("id", "name");
        }

        @Test
        @DisplayName("Root with singular reference round-trips correctly")
        void rootWithSingularReferenceRoundTrips() throws Exception {
            var addressId = new BasicAttribute("id", new AttributeLocation("addresses", "id"), new DataType.NumericType(19, 0));
            var addressRoot = new Root("addresses", List.of(addressId), IdDescriptor.longId("addresses"));

            var userId = new BasicAttribute("id", new AttributeLocation("users", "id"), new DataType.NumericType(19, 0));
            var address = new SingularReferenceAttribute("address", addressRoot,
                new JoinTableMapping(new AttributeLocation("users", "address_id"), "id"));
            var original = new Root("users", List.of(userId, address), IdDescriptor.longId("users"));

            var json = objectMapper.writeValueAsString(original);
            var deserialized = objectMapper.readValue(json, Root.class);

            assertThat(deserialized.primaryTableName()).isEqualTo("users");
            assertThat(deserialized.attributes()).hasSize(2);

            var deserializedRef = deserialized.attributes().stream()
                .filter(SingularReferenceAttribute.class::isInstance)
                .map(a -> (SingularReferenceAttribute) a)
                .findFirst()
                .orElseThrow();

            assertThat(deserializedRef.name()).isEqualTo("address");
            assertThat(deserializedRef.targetRoot().primaryTableName()).isEqualTo("addresses");
            assertThat(deserializedRef.mappingStrategy()).isInstanceOf(JoinTableMapping.class);
        }

        @Test
        @DisplayName("Root with plural reference round-trips correctly")
        void rootWithPluralReferenceRoundTrips() throws Exception {
            var orderId = new BasicAttribute("id", new AttributeLocation("orders", "id"), new DataType.NumericType(19, 0));
            var orderRoot = new Root("orders", List.of(orderId), IdDescriptor.longId("orders"));

            var customerId = new BasicAttribute("id", new AttributeLocation("customers", "id"), new DataType.NumericType(19, 0));
            var orders = new PluralReferenceAttribute("orders", orderRoot,
                new InverseRootTableColumn("customer_id"));
            var original = new Root("customers", List.of(customerId, orders), IdDescriptor.longId("customers"));

            var json = objectMapper.writeValueAsString(original);
            var deserialized = objectMapper.readValue(json, Root.class);

            assertThat(deserialized.primaryTableName()).isEqualTo("customers");

            var deserializedRef = deserialized.attributes().stream()
                .filter(PluralReferenceAttribute.class::isInstance)
                .map(a -> (PluralReferenceAttribute) a)
                .findFirst()
                .orElseThrow();

            assertThat(deserializedRef.name()).isEqualTo("orders");
            assertThat(deserializedRef.targetRoot().primaryTableName()).isEqualTo("orders");
            assertThat(deserializedRef.mappingStrategy()).isInstanceOf(InverseRootTableColumn.class);
        }

        @Test
        @DisplayName("CollectionAttribute with CompositeElement round-trips correctly")
        void collectionAttributeWithCompositeElementRoundTrips() throws Exception {
            var product = new BasicAttribute("product", new AttributeLocation("order_items", "product"), new DataType.StringType());
            var quantity = new BasicAttribute("quantity", new AttributeLocation("order_items", "quantity"), new DataType.NumericType(10, 2));
            var original = new CollectionAttribute("items", "order_items",
                new CompositeElement(Set.of(product, quantity)));

            var json = objectMapper.writeValueAsString(original);
            var deserialized = objectMapper.readValue(json, CollectionAttribute.class);

            assertThat(deserialized.name()).isEqualTo("items");
            assertThat(deserialized.tableName()).isEqualTo("order_items");
            assertThat(deserialized.elementType()).isInstanceOf(CompositeElement.class);

            var element = (CompositeElement) deserialized.elementType();
            assertThat(element.attributes())
                .extracting(Attribute::name)
                .containsExactlyInAnyOrder("product", "quantity");
        }

        @Test
        @DisplayName("ModelSpace round-trips correctly")
        void modelSpaceRoundTrips() throws Exception {
            var userId = new BasicAttribute("id", new AttributeLocation("users", "id"), new DataType.NumericType(19, 0));
            var userRoot = new Root("users", List.of(userId), IdDescriptor.longId("users"));

            var orderId = new BasicAttribute("id", new AttributeLocation("orders", "id"), new DataType.NumericType(19, 0));
            var orderRoot = new Root("orders", List.of(orderId), IdDescriptor.longId("orders"));

            var original = new ModelSpace(Set.of(userRoot, orderRoot));

            var json = objectMapper.writeValueAsString(original);
            var deserialized = objectMapper.readValue(json, ModelSpace.class);

            assertThat(deserialized.roots())
                .extracting(Root::primaryTableName)
                .containsExactlyInAnyOrder("users", "orders");
        }
    }

    @Nested
    @DisplayName("Circular reference handling")
    class CircularReferenceHandling {

        @Test
        @DisplayName("Bidirectional reference uses identity for second occurrence")
        void bidirectionalReferenceUsesIdentity() throws Exception {
            // Customer -> Orders -> Customer (circular)
            var customerId = new BasicAttribute("id", new AttributeLocation("customers", "id"), new DataType.NumericType(19, 0));
            var orderId = new BasicAttribute("id", new AttributeLocation("orders", "id"), new DataType.NumericType(19, 0));

            // Create customer root first (will add orders later)
            var customerRoot = new Root("customers", List.of(customerId), IdDescriptor.longId("customers"));

            // Order references customer
            var orderCustomer = new SingularReferenceAttribute("customer", customerRoot,
                new JoinTableMapping(new AttributeLocation("orders", "customer_id"), "id"));
            var orderRoot = new Root("orders", List.of(orderId, orderCustomer), IdDescriptor.longId("orders"));

            // Customer references orders (circular back)
            var customerOrders = new PluralReferenceAttribute("orders", orderRoot,
                new InverseRootTableColumn("customer_id"));
            var fullCustomerRoot = new Root("customers", List.of(customerId, customerOrders), IdDescriptor.longId("customers"));

            var json = objectMapper.writeValueAsString(fullCustomerRoot);

            // Second occurrence of "customers" should be just the id reference
            assertThat(json).contains("\"primaryTableName\":\"customers\"");

            // Deserialize and verify structure is preserved
            var deserialized = objectMapper.readValue(json, Root.class);
            assertThat(deserialized.primaryTableName()).isEqualTo("customers");

            var ordersRef = deserialized.attributes().stream()
                .filter(PluralReferenceAttribute.class::isInstance)
                .map(a -> (PluralReferenceAttribute) a)
                .findFirst()
                .orElseThrow();

            assertThat(ordersRef.targetRoot().primaryTableName()).isEqualTo("orders");

            var customerRef = ordersRef.targetRoot().attributes().stream()
                .filter(SingularReferenceAttribute.class::isInstance)
                .map(a -> (SingularReferenceAttribute) a)
                .findFirst()
                .orElseThrow();

            // The circular reference back to customers should be resolved
            assertThat(customerRef.targetRoot().primaryTableName()).isEqualTo("customers");
        }
    }
}
