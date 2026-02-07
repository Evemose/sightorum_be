package com.rorm.dataimport.hierarchical;

import com.rorm.dataimport.attribute.DetectedAttribute;
import com.rorm.dataimport.hierarchical.HierarchicalStructure.DetectedField;
import com.rorm.dataimport.hierarchical.HierarchicalStructure.DetectedRoot;
import com.rorm.metamodel.DataType;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    // ForceReference Override Tests

    @Test
    void shouldForceReferenceOnScalarField() {
        var fields = new LinkedHashMap<String, DetectedField>();
        fields.put("id", new DetectedField.Scalar("id", new DataType.NumericType(19, 0)));
        fields.put("author_id", new DetectedField.Scalar("author_id", new DataType.NumericType(19, 0)));

        var roots = Map.of("posts", DetectedRoot.primary("posts", fields));
        var structure = new HierarchicalStructure(roots);

        var overrides = List.<HierarchicalOverride>of(
            new HierarchicalOverride.ForceReference("author_id", "authors")
        );

        var schema = converter.convert(structure, "posts", Set.of("posts", "authors"), overrides);

        var authorAttr = schema.roots().get("posts").attributes().get("author_id");
        assertThat(authorAttr).isInstanceOf(DetectedAttribute.SingularReference.class);

        var ref = (DetectedAttribute.SingularReference) authorAttr;
        assertThat(ref.targetRootName()).isEqualTo("authors");
    }

    @Test
    void shouldForceReferenceOnScalarArray() {
        var fields = new LinkedHashMap<String, DetectedField>();
        fields.put("id", new DetectedField.Scalar("id", new DataType.NumericType(19, 0)));
        fields.put("tag_ids", new DetectedField.ScalarArray("tag_ids", new DataType.NumericType(19, 0)));

        var roots = Map.of("posts", DetectedRoot.primary("posts", fields));
        var structure = new HierarchicalStructure(roots);

        var overrides = List.<HierarchicalOverride>of(
            new HierarchicalOverride.ForceReference("tag_ids", "tags")
        );

        var schema = converter.convert(structure, "posts", Set.of("posts", "tags"), overrides);

        var tagsAttr = schema.roots().get("posts").attributes().get("tag_ids");
        assertThat(tagsAttr).isInstanceOf(DetectedAttribute.PluralReference.class);

        var ref = (DetectedAttribute.PluralReference) tagsAttr;
        assertThat(ref.targetRootName()).isEqualTo("tags");
    }

    @Test
    void shouldValidateForceReferenceTargetExists() {
        var fields = new LinkedHashMap<String, DetectedField>();
        fields.put("id", new DetectedField.Scalar("id", new DataType.NumericType(19, 0)));
        fields.put("author_id", new DetectedField.Scalar("author_id", new DataType.NumericType(19, 0)));

        var roots = Map.of("posts", DetectedRoot.primary("posts", fields));
        var structure = new HierarchicalStructure(roots);

        var overrides = List.<HierarchicalOverride>of(
            new HierarchicalOverride.ForceReference("author_id", "nonexistent")
        );

        assertThatThrownBy(() ->
            converter.convert(structure, "posts", Set.of("posts", "authors"), overrides)
        )
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("ForceReference")
            .hasMessageContaining("nonexistent");
    }

    // ForceBasic Override Tests

    @Test
    void shouldForceBasicOnComposite() {
        var addressFields = new LinkedHashMap<String, DetectedField>();
        addressFields.put("city", new DetectedField.Scalar("city", new DataType.StringType()));
        addressFields.put("zip", new DetectedField.Scalar("zip", new DataType.StringType()));

        var fields = new LinkedHashMap<String, DetectedField>();
        fields.put("id", new DetectedField.Scalar("id", new DataType.NumericType(19, 0)));
        fields.put("address", new DetectedField.Composite("address", addressFields));

        var roots = Map.of("users", DetectedRoot.primary("users", fields));
        var structure = new HierarchicalStructure(roots);

        var overrides = List.<HierarchicalOverride>of(
            new HierarchicalOverride.ForceBasic("address", new DataType.StringType())
        );

        var schema = converter.convert(structure, "users", Set.of(), overrides);

        var addressAttr = schema.roots().get("users").attributes().get("address");
        assertThat(addressAttr).isInstanceOf(DetectedAttribute.Basic.class);

        var basic = (DetectedAttribute.Basic) addressAttr;
        assertThat(basic.dataType()).isInstanceOf(DataType.StringType.class);
    }

    @Test
    void shouldForceBasicOnSingularObjectRef() {
        var authorFields = new LinkedHashMap<String, DetectedField>();
        authorFields.put("id", new DetectedField.Scalar("id", new DataType.NumericType(19, 0)));
        authorFields.put("name", new DetectedField.Scalar("name", new DataType.StringType()));

        var postFields = new LinkedHashMap<String, DetectedField>();
        postFields.put("id", new DetectedField.Scalar("id", new DataType.NumericType(19, 0)));
        postFields.put("author", new DetectedField.SingularObjectRef("author", "authors"));

        var roots = new LinkedHashMap<String, DetectedRoot>();
        roots.put("posts", DetectedRoot.primary("posts", postFields));
        roots.put("authors", DetectedRoot.primary("authors", authorFields));
        var structure = new HierarchicalStructure(roots);

        var overrides = List.<HierarchicalOverride>of(
            new HierarchicalOverride.ForceBasic("author", new DataType.NumericType(19, 0))
        );

        var schema = converter.convert(structure, "posts", Set.of(), overrides);

        var authorAttr = schema.roots().get("posts").attributes().get("author");
        assertThat(authorAttr).isInstanceOf(DetectedAttribute.Basic.class);

        var basic = (DetectedAttribute.Basic) authorAttr;
        assertThat(basic.dataType()).isInstanceOf(DataType.NumericType.class);
    }

    @Test
    void shouldForceBasicOnCompositeCollection() {
        var itemFields = new LinkedHashMap<String, DetectedField>();
        itemFields.put("sku", new DetectedField.Scalar("sku", new DataType.StringType()));
        itemFields.put("qty", new DetectedField.Scalar("qty", new DataType.NumericType(10, 0)));

        var fields = new LinkedHashMap<String, DetectedField>();
        fields.put("id", new DetectedField.Scalar("id", new DataType.NumericType(19, 0)));
        fields.put("items", new DetectedField.CompositeCollection("items", itemFields));

        var roots = Map.of("orders", DetectedRoot.primary("orders", fields));
        var structure = new HierarchicalStructure(roots);

        var overrides = List.<HierarchicalOverride>of(
            new HierarchicalOverride.ForceBasic("items", new DataType.StringType())
        );

        var schema = converter.convert(structure, "orders", Set.of(), overrides);

        var itemsAttr = schema.roots().get("orders").attributes().get("items");
        assertThat(itemsAttr).isInstanceOf(DetectedAttribute.Collection.class);

        var collection = (DetectedAttribute.Collection) itemsAttr;
        assertThat(collection.elementType()).isInstanceOf(DataType.StringType.class);
    }

    @Test
    void shouldForceBasicOnPluralObjectRef() {
        var tagFields = new LinkedHashMap<String, DetectedField>();
        tagFields.put("id", new DetectedField.Scalar("id", new DataType.NumericType(19, 0)));
        tagFields.put("name", new DetectedField.Scalar("name", new DataType.StringType()));

        var postFields = new LinkedHashMap<String, DetectedField>();
        postFields.put("id", new DetectedField.Scalar("id", new DataType.NumericType(19, 0)));
        postFields.put("tags", new DetectedField.PluralObjectRef("tags", "post_tag"));

        var roots = new LinkedHashMap<String, DetectedRoot>();
        roots.put("posts", DetectedRoot.primary("posts", postFields));
        roots.put("post_tag", DetectedRoot.child("post_tag", tagFields, "posts", "tags"));
        var structure = new HierarchicalStructure(roots);

        var overrides = List.<HierarchicalOverride>of(
            new HierarchicalOverride.ForceBasic("tags", new DataType.NumericType(19, 0))
        );

        var schema = converter.convert(structure, "posts", Set.of(), overrides);

        var tagsAttr = schema.roots().get("posts").attributes().get("tags");
        assertThat(tagsAttr).isInstanceOf(DetectedAttribute.Collection.class);

        var collection = (DetectedAttribute.Collection) tagsAttr;
        assertThat(collection.elementType()).isInstanceOf(DataType.NumericType.class);
    }

    // IdOverride Tests

    @Test
    void shouldOverrideIdFieldName() {
        var fields = new LinkedHashMap<String, DetectedField>();
        fields.put("username", new DetectedField.Scalar("username", new DataType.StringType()));
        fields.put("name", new DetectedField.Scalar("name", new DataType.StringType()));

        var roots = Map.of("users", DetectedRoot.primary("users", fields));
        var structure = new HierarchicalStructure(roots);

        var overrides = List.<HierarchicalOverride>of(
            new HierarchicalOverride.IdOverride("users", "username", null)
        );

        var schema = converter.convert(structure, "users", Set.of(), overrides);

        var idColumn = schema.roots().get("users").idColumn();
        assertThat(idColumn.attributeName()).isEqualTo("username");
        assertThat(idColumn.columnName()).isEqualTo("username");
        assertThat(idColumn.dataType()).isInstanceOf(DataType.StringType.class);
    }

    @Test
    void shouldOverrideIdDataType() {
        var fields = new LinkedHashMap<String, DetectedField>();
        fields.put("id", new DetectedField.Scalar("id", new DataType.NumericType(19, 0)));
        fields.put("name", new DetectedField.Scalar("name", new DataType.StringType()));

        var roots = Map.of("users", DetectedRoot.primary("users", fields));
        var structure = new HierarchicalStructure(roots);

        var overrides = List.<HierarchicalOverride>of(
            new HierarchicalOverride.IdOverride("users", null, new DataType.StringType())
        );

        var schema = converter.convert(structure, "users", Set.of(), overrides);

        var idColumn = schema.roots().get("users").idColumn();
        assertThat(idColumn.attributeName()).isEqualTo("id");
        assertThat(idColumn.dataType()).isInstanceOf(DataType.StringType.class);
    }

    @Test
    void shouldOverrideBothIdFieldNameAndDataType() {
        var fields = new LinkedHashMap<String, DetectedField>();
        fields.put("email", new DetectedField.Scalar("email", new DataType.StringType()));
        fields.put("name", new DetectedField.Scalar("name", new DataType.StringType()));

        var roots = Map.of("users", DetectedRoot.primary("users", fields));
        var structure = new HierarchicalStructure(roots);

        var overrides = List.<HierarchicalOverride>of(
            new HierarchicalOverride.IdOverride("users", "email", new DataType.StringType())
        );

        var schema = converter.convert(structure, "users", Set.of(), overrides);

        var idColumn = schema.roots().get("users").idColumn();
        assertThat(idColumn.attributeName()).isEqualTo("email");
        assertThat(idColumn.columnName()).isEqualTo("email");
        assertThat(idColumn.dataType()).isInstanceOf(DataType.StringType.class);
    }

    @Test
    void shouldValidateIdOverrideFieldExists() {
        var fields = new LinkedHashMap<String, DetectedField>();
        fields.put("id", new DetectedField.Scalar("id", new DataType.NumericType(19, 0)));
        fields.put("name", new DetectedField.Scalar("name", new DataType.StringType()));

        var roots = Map.of("users", DetectedRoot.primary("users", fields));
        var structure = new HierarchicalStructure(roots);

        var overrides = List.<HierarchicalOverride>of(
            new HierarchicalOverride.IdOverride("users", "nonexistent", null)
        );

        assertThatThrownBy(() ->
            converter.convert(structure, "users", Set.of(), overrides)
        )
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("IdOverride")
            .hasMessageContaining("nonexistent")
            .hasMessageContaining("does not exist");
    }

    @Test
    void shouldValidateIdOverrideFieldIsScalar() {
        var addressFields = new LinkedHashMap<String, DetectedField>();
        addressFields.put("city", new DetectedField.Scalar("city", new DataType.StringType()));

        var fields = new LinkedHashMap<String, DetectedField>();
        fields.put("id", new DetectedField.Scalar("id", new DataType.NumericType(19, 0)));
        fields.put("address", new DetectedField.Composite("address", addressFields));

        var roots = Map.of("users", DetectedRoot.primary("users", fields));
        var structure = new HierarchicalStructure(roots);

        var overrides = List.<HierarchicalOverride>of(
            new HierarchicalOverride.IdOverride("users", "address", null)
        );

        assertThatThrownBy(() ->
            converter.convert(structure, "users", Set.of(), overrides)
        )
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("IdOverride")
            .hasMessageContaining("address")
            .hasMessageContaining("not a scalar");
    }

    // ForceComposite Override Tests

    @Test
    void shouldForceCompositeOnSingularObjectRef() {
        // Create a structure where profile is a separate root (ObjectRef)
        var profileFields = new LinkedHashMap<String, DetectedField>();
        profileFields.put("id", new DetectedField.Scalar("id", new DataType.NumericType(19, 0)));
        profileFields.put("bio", new DetectedField.Scalar("bio", new DataType.StringType()));
        profileFields.put("avatar", new DetectedField.Scalar("avatar", new DataType.StringType()));

        var userFields = new LinkedHashMap<String, DetectedField>();
        userFields.put("id", new DetectedField.Scalar("id", new DataType.NumericType(19, 0)));
        userFields.put("name", new DetectedField.Scalar("name", new DataType.StringType()));
        userFields.put("profile", new DetectedField.SingularObjectRef("profile", "users_profile"));

        var roots = new LinkedHashMap<String, DetectedRoot>();
        roots.put("users", DetectedRoot.primary("users", userFields));
        roots.put("users_profile", DetectedRoot.child("users_profile", profileFields, "users", "profile"));
        var structure = new HierarchicalStructure(roots);

        // Apply ForceComposite to inline the profile back into users
        var overrides = List.<HierarchicalOverride>of(
            new HierarchicalOverride.ForceComposite("profile")
        );

        var schema = converter.convert(structure, "users", Set.of(), overrides);

        // Profile should now be a Composite instead of a Reference
        var profileAttr = schema.roots().get("users").attributes().get("profile");
        assertThat(profileAttr).isInstanceOf(DetectedAttribute.Composite.class);

        var composite = (DetectedAttribute.Composite) profileAttr;
        assertThat(composite.subAttributes()).containsKeys("id", "bio", "avatar");
    }

    @Test
    void shouldForceCompositeOnPluralObjectRef() {
        // Create a structure where items is a separate root (ObjectRef)
        var itemFields = new LinkedHashMap<String, DetectedField>();
        itemFields.put("id", new DetectedField.Scalar("id", new DataType.NumericType(19, 0)));
        itemFields.put("sku", new DetectedField.Scalar("sku", new DataType.StringType()));
        itemFields.put("quantity", new DetectedField.Scalar("quantity", new DataType.NumericType(10, 0)));

        var orderFields = new LinkedHashMap<String, DetectedField>();
        orderFields.put("id", new DetectedField.Scalar("id", new DataType.NumericType(19, 0)));
        orderFields.put("items", new DetectedField.PluralObjectRef("items", "orders_item"));

        var roots = new LinkedHashMap<String, DetectedRoot>();
        roots.put("orders", DetectedRoot.primary("orders", orderFields));
        roots.put("orders_item", DetectedRoot.child("orders_item", itemFields, "orders", "items"));
        var structure = new HierarchicalStructure(roots);

        // Apply ForceComposite to inline items back into orders
        var overrides = List.<HierarchicalOverride>of(
            new HierarchicalOverride.ForceComposite("items")
        );

        var schema = converter.convert(structure, "orders", Set.of(), overrides);

        // Items should now be a Composite instead of a Reference
        var itemsAttr = schema.roots().get("orders").attributes().get("items");
        assertThat(itemsAttr).isInstanceOf(DetectedAttribute.Composite.class);

        var composite = (DetectedAttribute.Composite) itemsAttr;
        assertThat(composite.subAttributes()).containsKeys("id", "sku", "quantity");
    }

    @Test
    void shouldKeepObjectRefWhenForceCompositeTargetNotFound() {
        // Create ObjectRef to non-existent root
        var userFields = new LinkedHashMap<String, DetectedField>();
        userFields.put("id", new DetectedField.Scalar("id", new DataType.NumericType(19, 0)));
        userFields.put("profile", new DetectedField.SingularObjectRef("profile", "missing_root"));

        var roots = Map.of("users", DetectedRoot.primary("users", userFields));
        var structure = new HierarchicalStructure(roots);

        var overrides = List.<HierarchicalOverride>of(
            new HierarchicalOverride.ForceComposite("profile")
        );

        var schema = converter.convert(structure, "users", Set.of(), overrides);

        // Should keep as reference since target root doesn't exist
        var profileAttr = schema.roots().get("users").attributes().get("profile");
        assertThat(profileAttr).isInstanceOf(DetectedAttribute.SingularReference.class);
    }
}
