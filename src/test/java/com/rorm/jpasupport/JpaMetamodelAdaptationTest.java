package com.rorm.jpasupport;

import com.rorm.metamodel.*;
import com.rorm.metamodel.CollectionAttribute.BasicElement;
import com.rorm.metamodel.CollectionAttribute.CompositeElement;
import com.rorm.metamodel.ReferenceAttribute.InverseRootTableColumn;
import com.rorm.metamodel.ReferenceAttribute.JoinTableMapping;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.type;

class JpaMetamodelAdaptationTest extends AbstractHibernateTest {

    @Nested
    @DisplayName("Root extraction")
    class RootExtraction {

        @Test
        @DisplayName("extracts entity as Root with snake_case plural table name")
        void extractsEntityAsRoot() {
            assertThat(customerRoot.primaryTableName()).isEqualTo("customers");
            assertThat(customerRoot.attributes()).isNotEmpty();
        }
    }

    @Nested
    @DisplayName("Basic attributes")
    class BasicAttributes {

        @Test
        @DisplayName("extracts basic string attribute with snake_case column name")
        void extractsBasicStringAttribute() {
            assertThat(customerRoot.attributes())
                .filteredOn(attr -> attr.name().equals("firstName"))
                .singleElement()
                .asInstanceOf(type(BasicAttribute.class))
                .extracting(BasicAttribute::location)
                .isEqualTo(new AttributeLocation("customers", "first_name"));
        }

        @Test
        @DisplayName("preserves simple column names without transformation")
        void preservesSimpleColumnNames() {
            assertThat(customerRoot.attributes())
                .filteredOn(attr -> attr.name().equals("email"))
                .singleElement()
                .asInstanceOf(type(BasicAttribute.class))
                .extracting(BasicAttribute::location)
                .isEqualTo(new AttributeLocation("customers", "email"));
        }

        @Test
        @DisplayName("extracts enum attribute as basic attribute")
        void extractsEnumAttribute() {
            assertThat(customerRoot.attributes())
                .filteredOn(attr -> attr.name().equals("status"))
                .singleElement()
                .asInstanceOf(type(BasicAttribute.class))
                .extracting(attr -> attr.location().column())
                .isEqualTo("status");
        }

        @Test
        @DisplayName("extracts date attribute with snake_case column name")
        void extractsDateAttribute() {
            assertThat(customerRoot.attributes())
                .filteredOn(attr -> attr.name().equals("registrationDate"))
                .singleElement()
                .asInstanceOf(type(BasicAttribute.class))
                .extracting(attr -> attr.location().column())
                .isEqualTo("registration_date");
        }
    }

    @Nested
    @DisplayName("Composite attributes (embeddables)")
    class CompositeAttributes {

        @Test
        @DisplayName("extracts embedded attribute as CompositeAttribute with nested fields")
        void extractsEmbeddedAttribute() {
            var address = findAttribute(customerRoot, "shippingAddress", CompositeAttribute.class);

            assertThat(address.attributes()).hasSize(5);

            var city = findAttribute(address.attributes(), "city", BasicAttribute.class);
            assertThat(city.location()).isEqualTo(new AttributeLocation("customers", "city"));

            var street = findAttribute(address.attributes(), "street", BasicAttribute.class);
            assertThat(street.location()).isEqualTo(new AttributeLocation("customers", "street"));

            var zipCode = findAttribute(address.attributes(), "zipCode", BasicAttribute.class);
            assertThat(zipCode.location()).isEqualTo(new AttributeLocation("customers", "zip_code"));

            var country = findAttribute(address.attributes(), "country", BasicAttribute.class);
            assertThat(country.location()).isEqualTo(new AttributeLocation("customers", "country"));

            var state = findAttribute(address.attributes(), "state", BasicAttribute.class);
            assertThat(state.location()).isEqualTo(new AttributeLocation("customers", "state"));
        }

        @Test
        @DisplayName("extracts nested embeddables preserving structure")
        void extractsNestedEmbeddables() {
            var socialLinks = findAttribute(profileRoot, "socialLinks", CompositeAttribute.class);

            var twitter = findAttribute(socialLinks.attributes(), "twitter", BasicAttribute.class);
            assertThat(twitter.location()).isEqualTo(new AttributeLocation("customer_profiles", "twitter"));

            var linkedin = findAttribute(socialLinks.attributes(), "linkedin", BasicAttribute.class);
            assertThat(linkedin.location()).isEqualTo(new AttributeLocation("customer_profiles", "linkedin"));
        }
    }

    @Nested
    @DisplayName("Reference attributes (singular associations)")
    class SingularReferenceAttributes {

        @Test
        @DisplayName("extracts OneToOne relationship with correct target root")
        void extractsOneToOneRelationship() {
            var profile = findAttribute(customerRoot, "profile", SingularReferenceAttribute.class);

            assertThat(profile.targetRoot().primaryTableName()).isEqualTo("customer_profiles");
            // mappedBy = "customer" means FK is on the other side (inverse side)
            assertThat(profile.mappingStrategy())
                .isInstanceOf(InverseRootTableColumn.class)
                .extracting(m -> ((InverseRootTableColumn) m).columnName())
                .isEqualTo("customer_id");
        }

        @Test
        @DisplayName("extracts ManyToOne relationship with FK location")
        void extractsManyToOneRelationship() {
            var customer = findAttribute(orderRoot, "customer", SingularReferenceAttribute.class);

            assertThat(customer.targetRoot().primaryTableName()).isEqualTo("customers");
            assertThat(customer.mappingStrategy())
                .isInstanceOf(JoinTableMapping.class)
                .extracting(m -> ((JoinTableMapping) m).joinColumnLocation())
                .isEqualTo(new AttributeLocation("orders", "customer_id"));
        }
    }

    @Nested
    @DisplayName("Plural reference attributes (collection associations)")
    class PluralReferenceAttributes {

        @Test
        @DisplayName("extracts OneToMany relationship pointing to related root")
        void extractsOneToManyRelationship() {
            var orders = findAttribute(customerRoot, "orders", PluralReferenceAttribute.class);

            assertThat(orders.targetRoot().primaryTableName()).isEqualTo("orders");
            // mappedBy = "customer" means FK is on the orders table (inverse side)
            assertThat(orders.mappingStrategy())
                .isInstanceOf(InverseRootTableColumn.class)
                .extracting(m -> ((InverseRootTableColumn) m).columnName())
                .isEqualTo("customer_id");
        }

        @Test
        @DisplayName("extracts ManyToMany relationship with join table name")
        void extractsManyToManyRelationship() {
            var interests = findAttribute(customerRoot, "interests", PluralReferenceAttribute.class);

            assertThat(interests.targetRoot().primaryTableName()).isEqualTo("interests");
            // ManyToMany uses join table
            assertThat(interests.mappingStrategy())
                .isInstanceOf(JoinTableMapping.class)
                .extracting(m -> ((JoinTableMapping) m).joinColumnLocation().table())
                .isEqualTo("customer_interests");
        }
    }

    @Nested
    @DisplayName("Collection attributes (element collections)")
    class CollectionAttributes {

        @Test
        @DisplayName("extracts basic element collection with BasicElement")
        void extractsBasicElementCollection() {
            var phones = findAttribute(customerRoot, "phoneNumbers", CollectionAttribute.class);

            assertThat(phones.tableName()).isEqualTo("customer_phone_numbers");
            assertThat(phones.elementType())
                .asInstanceOf(type(BasicElement.class))
                .extracting(CollectionAttribute.BasicElement::location)
                .isEqualTo(new AttributeLocation("customer_phone_numbers", "phone_number"));
        }

        @Test
        @DisplayName("extracts embeddable element collection with CompositeElement")
        void extractsEmbeddableElementCollection() {
            var items = findAttribute(orderRoot, "items", CollectionAttribute.class);

            assertThat(items.tableName()).isEqualTo("order_items");
            assertThat(items.elementType()).isInstanceOf(CompositeElement.class);

            var element = (CompositeElement) items.elementType();
            assertThat(element.attributes()).hasSize(3);

            var productName = findAttribute(element.attributes(), "productName", BasicAttribute.class);
            assertThat(productName.location()).isEqualTo(new AttributeLocation("order_items", "product_name"));

            var quantity = findAttribute(element.attributes(), "quantity", BasicAttribute.class);
            assertThat(quantity.location()).isEqualTo(new AttributeLocation("order_items", "quantity"));

            var unitPrice = findAttribute(element.attributes(), "unitPrice", BasicAttribute.class);
            assertThat(unitPrice.location()).isEqualTo(new AttributeLocation("order_items", "unit_price"));
        }
    }

    private static <T extends Attribute> T findAttribute(
        com.rorm.metamodel.Root root, String name, Class<T> type
    ) {
        return findAttribute(root.attributes(), name, type);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Attribute> T findAttribute(
        java.util.Collection<Attribute> attributes, String name, Class<T> type
    ) {
        return (T) attributes.stream()
            .filter(attr -> attr.name().equals(name))
            .filter(type::isInstance)
            .findFirst()
            .orElseThrow(() -> new AssertionError("Attribute not found: " + name + " of type " + type.getSimpleName()));
    }
}
