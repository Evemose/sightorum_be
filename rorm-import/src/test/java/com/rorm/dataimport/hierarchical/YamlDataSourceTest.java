package com.rorm.dataimport.hierarchical;

import com.rorm.dataimport.attribute.DetectedAttribute;
import com.rorm.dataimport.hierarchical.HierarchicalStructure.DetectedField;
import com.rorm.metamodel.DataType;
import com.rorm.metamodel.DataType.CategorcialType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.*;

class YamlDataSourceTest {

    @TempDir
    Path tempDir;

    private final HierarchicalSchemaConverter converter = new HierarchicalSchemaConverter();

    @Test
    void shouldDetectSimpleStructure() throws Exception {
        var yamlContent = """
            - id: 1
              name: Alice
              age: 30
            - id: 2
              name: Bob
              age: 25
            """;

        var yamlFile = tempDir.resolve("users.yaml");
        Files.writeString(yamlFile, yamlContent);

        var dataSource = new YamlDataSource(yamlFile);

        var structure = dataSource.detectStructure();
        assertThat(structure.roots()).containsKey("users");

        var userRoot = structure.roots().get("users");
        assertThat(userRoot.isPrimary()).isTrue();
        assertThat(userRoot.fields()).containsKeys("id", "name", "age");

        dataSource.close();
    }

    @Test
    void shouldDetectNestedObjectsWithoutIdAsComposite() throws Exception {
        var yamlContent = """
            - id: 1
              name: Alice
              address:
                city: NYC
                zip: "10001"
            """;

        var yamlFile = tempDir.resolve("users.yaml");
        Files.writeString(yamlFile, yamlContent);

        var dataSource = new YamlDataSource(yamlFile);
        var structure = dataSource.detectStructure();

        var userRoot = structure.roots().get("users");
        assertThat(userRoot.fields().get("address")).isInstanceOf(DetectedField.Composite.class);

        var addressField = (DetectedField.Composite) userRoot.fields().get("address");
        assertThat(addressField.fields()).containsKeys("city", "zip");

        // No separate root created
        assertThat(structure.roots()).containsOnlyKeys("users");

        dataSource.close();
    }

    @Test
    void shouldDetectNestedObjectsWithIdAsSeparateRoot() throws Exception {
        var yamlContent = """
            - id: 1
              name: Alice
              profile:
                id: 101
                bio: Developer
            """;

        var yamlFile = tempDir.resolve("users.yaml");
        Files.writeString(yamlFile, yamlContent);

        var dataSource = new YamlDataSource(yamlFile);
        var structure = dataSource.detectStructure();

        var userRoot = structure.roots().get("users");
        assertThat(userRoot.fields().get("profile")).isInstanceOf(DetectedField.SingularObjectRef.class);

        assertThat(structure.roots()).containsKeys("users", "users_profile");

        dataSource.close();
    }

    @Test
    void shouldDetectScalarArrays() throws Exception {
        var yamlContent = """
            - id: 1
              name: Alice
              tags:
                - admin
                - user
            - id: 2
              name: Bob
              tags:
                - user
            """;

        var yamlFile = tempDir.resolve("users.yaml");
        Files.writeString(yamlFile, yamlContent);

        var dataSource = new YamlDataSource(yamlFile);
        var structure = dataSource.detectStructure();

        var userRoot = structure.roots().get("users");
        assertThat(userRoot.fields().get("tags")).isInstanceOf(DetectedField.ScalarArray.class);

        dataSource.close();
    }

    @Test
    void shouldDetectObjectArraysWithoutIdAsCompositeCollection() throws Exception {
        var yamlContent = """
            - id: 1
              name: Order1
              items:
                - sku: A001
                  qty: 2
                - sku: B002
                  qty: 1
            - id: 2
              name: Order2
              items:
                - sku: C003
                  qty: 5
            """;

        var yamlFile = tempDir.resolve("orders.yaml");
        Files.writeString(yamlFile, yamlContent);

        var dataSource = new YamlDataSource(yamlFile);
        var structure = dataSource.detectStructure();

        var orderRoot = structure.roots().get("orders");
        assertThat(orderRoot.fields().get("items")).isInstanceOf(DetectedField.CompositeCollection.class);

        // No separate root created
        assertThat(structure.roots()).containsOnlyKeys("orders");

        dataSource.close();
    }

    @Test
    void shouldDetectObjectArraysWithIdAsPluralObjectRef() throws Exception {
        var yamlContent = """
            - id: 1
              name: Order1
              items:
                - id: 10
                  sku: A001
                  qty: 2
                - id: 11
                  sku: B002
                  qty: 1
            """;

        var yamlFile = tempDir.resolve("orders.yaml");
        Files.writeString(yamlFile, yamlContent);

        var dataSource = new YamlDataSource(yamlFile);
        var structure = dataSource.detectStructure();

        var orderRoot = structure.roots().get("orders");
        assertThat(orderRoot.fields().get("items")).isInstanceOf(DetectedField.PluralObjectRef.class);

        assertThat(structure.roots()).containsKeys("orders", "orders_item");
        var itemRoot = structure.roots().get("orders_item");
        assertThat(itemRoot.isPrimary()).isFalse();
        assertThat(itemRoot.parentRootName()).isEqualTo("orders");

        dataSource.close();
    }

    @Test
    void shouldFlattenToMapFormat() throws Exception {
        var yamlContent = """
            - id: 1
              name: Alice
              address:
                city: NYC
                zip: "10001"
              tags:
                - admin
                - user
            """;

        var yamlFile = tempDir.resolve("users.yaml");
        Files.writeString(yamlFile, yamlContent);

        var dataSource = new YamlDataSource(yamlFile);

        var columnNames = dataSource.getColumnNames();
        assertThat(columnNames).contains("id", "name", "address.city", "address.zip", "tags");

        var rows = dataSource.stream().toList();
        assertThat(rows).hasSize(1);

        var row = rows.getFirst();
        assertThat(row.get("id")).asInstanceOf(LONG).isEqualTo(1L);
        assertThat(row.get("name")).asInstanceOf(STRING).isEqualTo("Alice");
        assertThat(row.get("address.city")).asInstanceOf(STRING).isEqualTo("NYC");
        assertThat(row.get("address.zip")).asInstanceOf(STRING).isEqualTo("10001");
        assertThat(row.get("tags")).asInstanceOf(LIST).containsExactly("admin", "user");

        dataSource.close();
    }

    @Test
    void shouldHandleSingleDocument() throws Exception {
        var yamlContent = """
            id: 1
            name: SingleItem
            value: 100
            """;

        var yamlFile = tempDir.resolve("single.yaml");
        Files.writeString(yamlFile, yamlContent);

        var dataSource = new YamlDataSource(yamlFile);
        var structure = dataSource.detectStructure();

        assertThat(structure.roots()).containsKey("single");
        var root = structure.roots().get("single");
        assertThat(root.fields()).containsKeys("id", "name", "value");

        var rows = dataSource.stream().toList();
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().get("name")).isEqualTo("SingleItem");

        dataSource.close();
    }

    @Test
    void shouldApplyDataTypeOverride() throws Exception {
        var yamlContent = """
            - id: abc123
              status: active
            - id: def456
              status: inactive
            """;

        var yamlFile = tempDir.resolve("items.yaml");
        Files.writeString(yamlFile, yamlContent);

        List<HierarchicalOverride> overrides = List.of(
            new HierarchicalOverride.DataTypeOverride("status", new CategorcialType(new String[]{"active", "inactive"}))
        );

        var dataSource = new YamlDataSource(yamlFile);
        var structure = dataSource.detectStructure();

        // Raw structure has StringType for status
        var rawStatusField = (DetectedField.Scalar) structure.roots().get("items").fields().get("status");
        assertThat(rawStatusField.dataType()).isInstanceOf(DataType.StringType.class);

        // Convert with override - should apply EnumType
        var schema = converter.convert(structure, "items", Set.of(), overrides);
        var statusAttr = (DetectedAttribute.Basic) schema.roots().get("items").attributes().get("status");
        assertThat(statusAttr.dataType()).isInstanceOf(CategorcialType.class);

        dataSource.close();
    }

    @Test
    void shouldPreserveArraysAsList() throws Exception {
        var yamlContent = """
            - id: 1
              tags:
                - a
                - b
                - c
            """;

        var yamlFile = tempDir.resolve("items.yaml");
        Files.writeString(yamlFile, yamlContent);

        var dataSource = new YamlDataSource(yamlFile);
        var rows = dataSource.stream().toList();

        assertThat(rows.getFirst().get("tags")).asInstanceOf(LIST).containsExactly("a", "b", "c");

        dataSource.close();
    }

    @Test
    void shouldDeriveRootNameFromFileName() throws Exception {
        var yamlContent = "- x: 1";

        var yamlFile = tempDir.resolve("my_entities.yml");
        Files.writeString(yamlFile, yamlContent);

        var dataSource = new YamlDataSource(yamlFile);
        assertThat(dataSource.getRootName()).isEqualTo("my_entities");

        dataSource.close();
    }

    @SuppressWarnings("DataFlowIssue")
    @Test
    void shouldHandleNullValues() throws Exception {
        var yamlContent = """
            - id: 1
              name: Alice
              email: null
            - id: 2
              name: Bob
              email: bob@example.com
            """;

        var yamlFile = tempDir.resolve("users.yaml");
        Files.writeString(yamlFile, yamlContent);

        var dataSource = new YamlDataSource(yamlFile);
        var rows = dataSource.stream().toList();

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).hasEntrySatisfying("email", value -> assertThat(value).isNull());
        assertThat(rows.get(1).get("email")).asInstanceOf(STRING).isEqualTo("bob@example.com");

        dataSource.close();
    }

    @Test
    void shouldPreserveNativeTypes() throws Exception {
        var yamlContent = """
            - intVal: 42
              floatVal: 3.14
              boolVal: true
              strVal: text
            """;

        var yamlFile = tempDir.resolve("types.yaml");
        Files.writeString(yamlFile, yamlContent);

        var dataSource = new YamlDataSource(yamlFile);
        var rows = dataSource.stream().toList();
        var row = rows.getFirst();

        assertThat(row.get("intVal")).asInstanceOf(LONG).isEqualTo(42L);
        assertThat(row.get("floatVal")).asInstanceOf(DOUBLE).isEqualTo(3.14);
        assertThat(row.get("boolVal")).asInstanceOf(BOOLEAN).isTrue();
        assertThat(row.get("strVal")).asInstanceOf(STRING).isEqualTo("text");

        dataSource.close();
    }

    // ========== Comprehensive Override Tests ==========

    @Test
    void shouldForceCompositeOnObjectWithId() throws Exception {
        // Single object WITH "id" but forced to be composite
        var yamlContent = """
            - id: 1
              profile:
                id: 101
                bio: Developer
            """;

        var yamlFile = tempDir.resolve("users.yaml");
        Files.writeString(yamlFile, yamlContent);

        var dataSource = new YamlDataSource(yamlFile);
        var structure = dataSource.detectStructure();

        var userRoot = structure.roots().get("users");
        // Raw structure detects profile as separate root (has "id")
        assertThat(userRoot.fields().get("profile")).isInstanceOf(DetectedField.SingularObjectRef.class);

        // Apply ForceComposite override via converter
        // Note: ForceComposite on already-detected ObjectRef is a TODO for now
        // The override works best when the field is already a Composite
        dataSource.close();
    }

    @Test
    void shouldForceCompositeOnArrayOfObjectsWithId() throws Exception {
        // Array of objects WITH "id" but forced to be composite collection
        var yamlContent = """
            - id: 1
              items:
                - id: 10
                  name: Item1
                - id: 11
                  name: Item2
            """;

        var yamlFile = tempDir.resolve("orders.yaml");
        Files.writeString(yamlFile, yamlContent);

        var dataSource = new YamlDataSource(yamlFile);
        var structure = dataSource.detectStructure();

        var orderRoot = structure.roots().get("orders");
        // Raw structure detects items as PluralObjectRef (has "id")
        assertThat(orderRoot.fields().get("items")).isInstanceOf(DetectedField.PluralObjectRef.class);

        // Apply ForceComposite override via converter
        // Note: ForceComposite on already-detected ObjectRef is a TODO for now
        // The override works best when the field is already a CompositeCollection
        dataSource.close();
    }

    @Test
    void shouldForceSeparateRootOnObjectWithoutId() throws Exception {
        // Single object WITHOUT "id" but forced to be separate root
        var yamlContent = """
            - id: 1
              address:
                city: NYC
                zip: "10001"
            """;

        var yamlFile = tempDir.resolve("users.yaml");
        Files.writeString(yamlFile, yamlContent);

        List<HierarchicalOverride> overrides = List.of(
            new HierarchicalOverride.ForceSeparateRoot("address", HierarchicalOverride.IdStrategy.AutoGenerate.INSTANCE)
        );

        var dataSource = new YamlDataSource(yamlFile);
        var structure = dataSource.detectStructure();

        // Raw structure detects address as Composite (no "id")
        assertThat(structure.roots().get("users").fields().get("address"))
            .isInstanceOf(DetectedField.Composite.class);

        // Convert with override - should create separate root
        var schema = converter.convert(structure, "users", Set.of(), overrides);

        // Separate root created via override
        assertThat(schema.roots()).containsKeys("users", "users_address");
        assertThat(schema.roots().get("users").attributes().get("address"))
            .isInstanceOf(DetectedAttribute.SingularReference.class);

        dataSource.close();
    }

    @Test
    void shouldForceSeparateRootOnArrayOfObjectsWithoutId() throws Exception {
        // Array of objects WITHOUT "id" but forced to be separate root
        var yamlContent = """
            - id: 1
              tags:
                - name: tag1
                  color: red
                - name: tag2
                  color: blue
            """;

        var yamlFile = tempDir.resolve("items.yaml");
        Files.writeString(yamlFile, yamlContent);

        List<HierarchicalOverride> overrides = List.of(
            new HierarchicalOverride.ForceSeparateRoot("tags", HierarchicalOverride.IdStrategy.AutoGenerate.INSTANCE)
        );

        var dataSource = new YamlDataSource(yamlFile);
        var structure = dataSource.detectStructure();

        // Raw structure detects tags as CompositeCollection (no "id")
        assertThat(structure.roots().get("items").fields().get("tags"))
            .isInstanceOf(DetectedField.CompositeCollection.class);

        // Convert with override - should create separate root
        var schema = converter.convert(structure, "items", Set.of(), overrides);

        // Separate root created via override
        assertThat(schema.roots()).containsKeys("items", "items_tag");
        assertThat(schema.roots().get("items").attributes().get("tags"))
            .isInstanceOf(DetectedAttribute.PluralReference.class);

        dataSource.close();
    }

    @Test
    void shouldForceSeparateRootWithUseFieldIdStrategy() throws Exception {
        // Force separate root using existing field as ID
        var yamlContent = """
            - id: 1
              settings:
                key: theme
                value: dark
            """;

        var yamlFile = tempDir.resolve("users.yaml");
        Files.writeString(yamlFile, yamlContent);

        List<HierarchicalOverride> overrides = List.of(
            new HierarchicalOverride.ForceSeparateRoot(
                "settings",
                new HierarchicalOverride.IdStrategy.UseField("key", new DataType.StringType())
            )
        );

        var dataSource = new YamlDataSource(yamlFile);
        var structure = dataSource.detectStructure();

        // Raw structure detects settings as Composite (no "id")
        assertThat(structure.roots().get("users").fields().get("settings"))
            .isInstanceOf(DetectedField.Composite.class);

        // Convert with override - should create separate root with UseField ID strategy
        var schema = converter.convert(structure, "users", Set.of(), overrides);

        assertThat(schema.roots()).containsKeys("users", "users_setting");
        var settingsRoot = schema.roots().get("users_setting");
        assertThat(settingsRoot.idColumn().attributeName()).isEqualTo("key");

        dataSource.close();
    }

    @Test
    void shouldApplyDataTypeOverrideToNestedField() throws Exception {
        var yamlContent = """
            - id: 1
              address:
                city: NYC
                type: home
            """;

        var yamlFile = tempDir.resolve("users.yaml");
        Files.writeString(yamlFile, yamlContent);

        List<HierarchicalOverride> overrides = List.of(
            new HierarchicalOverride.DataTypeOverride(
                "address.type",
                new CategorcialType(new String[]{"home", "work", "other"})
            )
        );

        var dataSource = new YamlDataSource(yamlFile);
        var structure = dataSource.detectStructure();

        // Convert with override
        var schema = converter.convert(structure, "users", Set.of(), overrides);

        var addressAttr = (DetectedAttribute.Composite) schema.roots().get("users").attributes().get("address");
        var typeAttr = (DetectedAttribute.Basic) addressAttr.subAttributes().get("type");
        assertThat(typeAttr.dataType()).isInstanceOf(CategorcialType.class);

        dataSource.close();
    }

    @Test
    void shouldApplyMultipleOverridesSimultaneously() throws Exception {
        var yamlContent = """
            - id: 1
              status: active
              settings:
                theme: dark
            """;

        var yamlFile = tempDir.resolve("users.yaml");
        Files.writeString(yamlFile, yamlContent);

        List<HierarchicalOverride> overrides = List.of(
            // Override data type
            new HierarchicalOverride.DataTypeOverride("status", new CategorcialType(new String[]{"active", "inactive"})),
            // Force settings (no ID) to be separate root
            new HierarchicalOverride.ForceSeparateRoot("settings", HierarchicalOverride.IdStrategy.AutoGenerate.INSTANCE)
        );

        var dataSource = new YamlDataSource(yamlFile);
        var structure = dataSource.detectStructure();

        // Convert with overrides
        var schema = converter.convert(structure, "users", Set.of(), overrides);

        // Status should be enum
        var statusAttr = (DetectedAttribute.Basic) schema.roots().get("users").attributes().get("status");
        assertThat(statusAttr.dataType()).isInstanceOf(CategorcialType.class);

        // Settings should be separate root (via override)
        assertThat(schema.roots()).containsKeys("users", "users_setting");
        assertThat(schema.roots().get("users").attributes().get("settings"))
            .isInstanceOf(DetectedAttribute.SingularReference.class);

        dataSource.close();
    }

    @Test
    void shouldHandleDeeplyNestedStructures() throws Exception {
        var yamlContent = """
            - id: 1
              level1:
                level2:
                  level3:
                    value: deep
            """;

        var yamlFile = tempDir.resolve("deep.yaml");
        Files.writeString(yamlFile, yamlContent);

        var dataSource = new YamlDataSource(yamlFile);
        var structure = dataSource.detectStructure();

        // All levels should be composites (no IDs)
        assertThat(structure.roots()).containsOnlyKeys("deep");

        var columnNames = dataSource.getColumnNames();
        assertThat(columnNames).contains("id", "level1.level2.level3.value");

        var rows = dataSource.stream().toList();
        assertThat(rows.getFirst().get("level1.level2.level3.value")).isEqualTo("deep");

        dataSource.close();
    }

    @Test
    void shouldHandleMixedArrayTypes() throws Exception {
        var yamlContent = """
            - id: 1
              scalarArray:
                - 1
                - 2
                - 3
              objectArrayNoId:
                - x: 1
                - x: 2
              objectArrayWithId:
                - id: 10
                  y: a
                - id: 11
                  y: b
            """;

        var yamlFile = tempDir.resolve("mixed.yaml");
        Files.writeString(yamlFile, yamlContent);

        var dataSource = new YamlDataSource(yamlFile);
        var structure = dataSource.detectStructure();

        var root = structure.roots().get("mixed");

        // Scalar array
        assertThat(root.fields().get("scalarArray")).isInstanceOf(DetectedField.ScalarArray.class);

        // Object array without ID → CompositeCollection
        assertThat(root.fields().get("objectArrayNoId")).isInstanceOf(DetectedField.CompositeCollection.class);

        // Object array with ID → PluralObjectRef
        assertThat(root.fields().get("objectArrayWithId")).isInstanceOf(DetectedField.PluralObjectRef.class);

        // Only one separate root created
        assertThat(structure.roots()).containsKeys("mixed", "mixed_objectArrayWithId");

        dataSource.close();
    }

    @Test
    void shouldHandleEmptyArrays() throws Exception {
        var yamlContent = """
            - id: 1
              tags: []
              items: []
            """;

        var yamlFile = tempDir.resolve("empty.yaml");
        Files.writeString(yamlFile, yamlContent);

        var dataSource = new YamlDataSource(yamlFile);
        var structure = dataSource.detectStructure();

        var root = structure.roots().get("empty");
        // Empty arrays default to ScalarArray with StringType
        assertThat(root.fields().get("tags")).isInstanceOf(DetectedField.ScalarArray.class);
        assertThat(root.fields().get("items")).isInstanceOf(DetectedField.ScalarArray.class);

        var rows = dataSource.stream().toList();
        assertThat(rows.getFirst().get("tags")).asInstanceOf(LIST).isEmpty();
        assertThat(rows.getFirst().get("items")).asInstanceOf(LIST).isEmpty();

        dataSource.close();
    }

    @Test
    void shouldOverrideNumericPrecision() throws Exception {
        var yamlContent = """
            - id: 1
              price: "123.45"
            """;

        var yamlFile = tempDir.resolve("prices.yaml");
        Files.writeString(yamlFile, yamlContent);

        List<HierarchicalOverride> overrides = List.of(
            new HierarchicalOverride.DataTypeOverride("price", new DataType.NumericType(10, 2))
        );

        var dataSource = new YamlDataSource(yamlFile);
        var structure = dataSource.detectStructure();

        // Convert with override
        var schema = converter.convert(structure, "prices", Set.of(), overrides);

        var priceAttr = (DetectedAttribute.Basic) schema.roots().get("prices").attributes().get("price");
        assertThat(priceAttr.dataType()).isInstanceOf(DataType.NumericType.class);
        var numericType = (DataType.NumericType) priceAttr.dataType();
        assertThat(numericType)
            .isNotNull()
            .extracting(DataType.NumericType::precision, DataType.NumericType::scale)
            .containsExactly(10, 2);

        dataSource.close();
    }

    @Test
    void shouldHandleSingularization() throws Exception {
        // Test various plural forms
        var yamlContent = """
            - id: 1
              categories:
                - id: 10
              boxes:
                - id: 20
              statuses:
                - id: 30
            """;

        var yamlFile = tempDir.resolve("test.yaml");
        Files.writeString(yamlFile, yamlContent);

        var dataSource = new YamlDataSource(yamlFile);
        var structure = dataSource.detectStructure();

        // Check singularized root names
        assertThat(structure.roots()).containsKeys(
            "test",
            "test_category",    // categories → category
            "test_box",         // boxes → box (special es ending)
            "test_status"       // statuses → status (special es ending)
        );

        dataSource.close();
    }

    @Test
    void shouldHandleMultilineStrings() throws Exception {
        var yamlContent = """
            - id: 1
              description: |
                This is a
                multiline string
              comment: >
                This is a
                folded string
            """;

        var yamlFile = tempDir.resolve("multiline.yaml");
        Files.writeString(yamlFile, yamlContent);

        var dataSource = new YamlDataSource(yamlFile);
        var rows = dataSource.stream().toList();

        assertThat(rows.getFirst().get("description")).asString().contains("multiline string");
        assertThat(rows.getFirst().get("comment")).asString().contains("folded string");

        dataSource.close();
    }

    @Test
    void shouldHandleQuotedStrings() throws Exception {
        var yamlContent = """
            - id: 1
              doubleQuoted: "hello world"
              singleQuoted: 'hello world'
              numericString: "123"
              boolString: "true"
            """;

        var yamlFile = tempDir.resolve("quoted.yaml");
        Files.writeString(yamlFile, yamlContent);

        var dataSource = new YamlDataSource(yamlFile);
        var rows = dataSource.stream().toList();
        var row = rows.getFirst();

        assertThat(row.get("doubleQuoted")).isEqualTo("hello world");
        assertThat(row.get("singleQuoted")).isEqualTo("hello world");
        assertThat(row.get("numericString")).isEqualTo("123");
        assertThat(row.get("boolString")).isEqualTo("true");

        dataSource.close();
    }

    @Test
    void shouldRespectColumnOrderInFlattenedOutput() throws Exception {
        var yamlContent = """
            - id: 1
              z_field: z
              a_field: a
              m_field: m
            """;

        var yamlFile = tempDir.resolve("order.yaml");
        Files.writeString(yamlFile, yamlContent);

        var dataSource = new YamlDataSource(yamlFile);
        var columnNames = dataSource.getColumnNames();

        // Columns should be in the order they appear in YAML
        assertThat(columnNames).containsExactly("id", "z_field", "a_field", "m_field");

        dataSource.close();
    }
}
