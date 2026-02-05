package com.rorm.dataimport.hierarchical;

import com.rorm.dataimport.attribute.DetectedAttribute;
import com.rorm.dataimport.hierarchical.HierarchicalStructure.DetectedField;
import com.rorm.dataimport.hierarchical.HierarchicalStructure.DetectedRoot;
import com.rorm.metamodel.DataType;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HierarchicalSchemaConverterTest {

    private final HierarchicalSchemaConverter converter = new HierarchicalSchemaConverter();

    @Test
    void shouldConvertSimpleStructure() {
        var fields = new LinkedHashMap<String, DetectedField>();
        fields.put("id", new DetectedField.Scalar("id", new DataType.NumericType(19, 0)));
        fields.put("name", new DetectedField.Scalar("name", new DataType.StringType()));

        var roots = Map.of("users", DetectedRoot.primary("users", fields));
        var structure = new HierarchicalStructure(roots);

        var schema = converter.convert(structure, "users");

        assertThat(schema.roots()).containsKey("users");
        var userRoot = schema.roots().get("users");
        assertThat(userRoot.attributes()).containsKeys("id", "name");
        assertThat(userRoot.attributes().get("id")).isInstanceOf(DetectedAttribute.Basic.class);
        assertThat(userRoot.attributes().get("name")).isInstanceOf(DetectedAttribute.Basic.class);
    }

    @Test
    void shouldConvertCompositeFields() {
        var addressFields = new LinkedHashMap<String, DetectedField>();
        addressFields.put("city", new DetectedField.Scalar("city", new DataType.StringType()));
        addressFields.put("zip", new DetectedField.Scalar("zip", new DataType.StringType()));

        var fields = new LinkedHashMap<String, DetectedField>();
        fields.put("id", new DetectedField.Scalar("id", new DataType.NumericType(19, 0)));
        fields.put("address", new DetectedField.Composite("address", addressFields));

        var roots = Map.of("users", DetectedRoot.primary("users", fields));
        var structure = new HierarchicalStructure(roots);

        var schema = converter.convert(structure, "users");

        var addressAttr = schema.roots().get("users").attributes().get("address");
        assertThat(addressAttr).isInstanceOf(DetectedAttribute.Composite.class);

        var compositeAddr = (DetectedAttribute.Composite) addressAttr;
        assertThat(compositeAddr.subAttributes()).containsKeys("city", "zip");
    }

    @Test
    void shouldConvertScalarArrays() {
        var fields = new LinkedHashMap<String, DetectedField>();
        fields.put("id", new DetectedField.Scalar("id", new DataType.NumericType(19, 0)));
        fields.put("tags", new DetectedField.ScalarArray("tags", new DataType.StringType()));

        var roots = Map.of("users", DetectedRoot.primary("users", fields));
        var structure = new HierarchicalStructure(roots);

        var schema = converter.convert(structure, "users");

        var tagsAttr = schema.roots().get("users").attributes().get("tags");
        assertThat(tagsAttr).isInstanceOf(DetectedAttribute.Collection.class);

        var collectionAttr = (DetectedAttribute.Collection) tagsAttr;
        assertThat(collectionAttr.separator()).isEqualTo(",");
        assertThat(collectionAttr.elementType()).isInstanceOf(DataType.StringType.class);
    }

    @Test
    void shouldConvertCompositeCollection() {
        var elementFields = new LinkedHashMap<String, DetectedField>();
        elementFields.put("sku", new DetectedField.Scalar("sku", new DataType.StringType()));
        elementFields.put("qty", new DetectedField.Scalar("qty", new DataType.NumericType(10, 0)));

        var fields = new LinkedHashMap<String, DetectedField>();
        fields.put("id", new DetectedField.Scalar("id", new DataType.NumericType(19, 0)));
        fields.put("items", new DetectedField.CompositeCollection("items", elementFields));

        var roots = Map.of("orders", DetectedRoot.primary("orders", fields));
        var structure = new HierarchicalStructure(roots);

        var schema = converter.convert(structure, "orders");

        var itemsAttr = schema.roots().get("orders").attributes().get("items");
        assertThat(itemsAttr).isInstanceOf(DetectedAttribute.Composite.class);
    }

    @Test
    void shouldConvertSingularObjectRef() {
        var profileFields = new LinkedHashMap<String, DetectedField>();
        profileFields.put("id", new DetectedField.Scalar("id", new DataType.NumericType(19, 0)));
        profileFields.put("bio", new DetectedField.Scalar("bio", new DataType.StringType()));

        var userFields = new LinkedHashMap<String, DetectedField>();
        userFields.put("id", new DetectedField.Scalar("id", new DataType.NumericType(19, 0)));
        userFields.put("profile", new DetectedField.SingularObjectRef("profile", "users_profile"));

        var roots = new LinkedHashMap<String, DetectedRoot>();
        roots.put("users", DetectedRoot.primary("users", userFields));
        roots.put("users_profile", DetectedRoot.child("users_profile", profileFields, "users", "profile"));
        var structure = new HierarchicalStructure(roots);

        var schema = converter.convert(structure, "users");

        var profileAttr = schema.roots().get("users").attributes().get("profile");
        assertThat(profileAttr).isInstanceOf(DetectedAttribute.SingularReference.class);
        assertThat(((DetectedAttribute.SingularReference) profileAttr).targetRootName()).isEqualTo("users_profile");
    }

    @Test
    void shouldConvertPluralObjectRef() {
        var itemFields = new LinkedHashMap<String, DetectedField>();
        itemFields.put("id", new DetectedField.Scalar("id", new DataType.NumericType(19, 0)));
        itemFields.put("sku", new DetectedField.Scalar("sku", new DataType.StringType()));

        var orderFields = new LinkedHashMap<String, DetectedField>();
        orderFields.put("id", new DetectedField.Scalar("id", new DataType.NumericType(19, 0)));
        orderFields.put("items", new DetectedField.PluralObjectRef("items", "orders_item"));

        var roots = new LinkedHashMap<String, DetectedRoot>();
        roots.put("orders", DetectedRoot.primary("orders", orderFields));
        roots.put("orders_item", DetectedRoot.child("orders_item", itemFields, "orders", "items"));
        var structure = new HierarchicalStructure(roots);

        var schema = converter.convert(structure, "orders");

        // Check parent has plural reference
        var itemsAttr = schema.roots().get("orders").attributes().get("items");
        assertThat(itemsAttr).isInstanceOf(DetectedAttribute.PluralReference.class);
        assertThat(((DetectedAttribute.PluralReference) itemsAttr).targetRootName()).isEqualTo("orders_item");

        // Check child has parent reference
        var childRoot = schema.roots().get("orders_item");
        assertThat(childRoot.attributes()).containsKey("orders_id");
        var parentRefAttr = childRoot.attributes().get("orders_id");
        assertThat(parentRefAttr).isInstanceOf(DetectedAttribute.SingularReference.class);
        assertThat(((DetectedAttribute.SingularReference) parentRefAttr).targetRootName()).isEqualTo("orders");
    }

    @Test
    void shouldGenerateSyntheticIdWhenNoIdField() {
        var fields = new LinkedHashMap<String, DetectedField>();
        fields.put("name", new DetectedField.Scalar("name", new DataType.StringType()));

        var roots = Map.of("items", DetectedRoot.primary("items", fields));
        var structure = new HierarchicalStructure(roots);

        var schema = converter.convert(structure, "items");

        var idColumn = schema.roots().get("items").idColumn();
        assertThat(idColumn.attributeName()).isEqualTo("items_synthetic_id");
        assertThat(idColumn.dataType()).isInstanceOf(DataType.NumericType.class);
    }

    @Test
    void shouldUseExistingIdField() {
        var fields = new LinkedHashMap<String, DetectedField>();
        fields.put("id", new DetectedField.Scalar("id", new DataType.StringType()));
        fields.put("name", new DetectedField.Scalar("name", new DataType.StringType()));

        var roots = Map.of("items", DetectedRoot.primary("items", fields));
        var structure = new HierarchicalStructure(roots);

        var schema = converter.convert(structure, "items");

        var idColumn = schema.roots().get("items").idColumn();
        assertThat(idColumn.attributeName()).isEqualTo("id");
        assertThat(idColumn.columnName()).isEqualTo("id");
        assertThat(idColumn.dataType()).isInstanceOf(DataType.StringType.class);
    }
}
