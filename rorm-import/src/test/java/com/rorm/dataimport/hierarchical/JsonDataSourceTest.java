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

class JsonDataSourceTest {

    @TempDir
    Path tempDir;

    private final HierarchicalSchemaConverter converter = new HierarchicalSchemaConverter();

    @Test
    void shouldDetectSimpleStructure() throws Exception {
        var jsonContent = """
            [
                {"id": 1, "name": "Alice", "age": 30},
                {"id": 2, "name": "Bob", "age": 25}
            ]
            """;

        var jsonFile = tempDir.resolve("users.json");
        Files.writeString(jsonFile, jsonContent);

        var dataSource = new JsonDataSource(jsonFile);

        var structure = dataSource.detectStructure();
        assertThat(structure.roots()).containsKey("users");

        var userRoot = structure.roots().get("users");
        assertThat(userRoot.isPrimary()).isTrue();
        assertThat(userRoot.fields()).containsKeys("id", "name", "age");

        assertThat(userRoot.fields().get("id")).isInstanceOf(DetectedField.Scalar.class);
        assertThat(userRoot.fields().get("name")).isInstanceOf(DetectedField.Scalar.class);
        assertThat(userRoot.fields().get("age")).isInstanceOf(DetectedField.Scalar.class);

        dataSource.close();
    }

    @Test
    void shouldDetectNestedObjectsWithoutIdAsComposite() throws Exception {
        // Nested objects WITHOUT "id" field → Composite (embedded)
        var jsonContent = """
            [
                {"id": 1, "name": "Alice", "address": {"city": "NYC", "zip": "10001"}},
                {"id": 2, "name": "Bob", "address": {"city": "LA", "zip": "90001"}}
            ]
            """;

        var jsonFile = tempDir.resolve("users.json");
        Files.writeString(jsonFile, jsonContent);

        var dataSource = new JsonDataSource(jsonFile);
        var structure = dataSource.detectStructure();

        var userRoot = structure.roots().get("users");
        assertThat(userRoot.fields().get("address")).isInstanceOf(DetectedField.Composite.class);

        var addressField = (DetectedField.Composite) userRoot.fields().get("address");
        assertThat(addressField.fields()).containsKeys("city", "zip");

        // No separate root created for address
        assertThat(structure.roots()).containsOnlyKeys("users");

        dataSource.close();
    }

    @Test
    void shouldDetectNestedObjectsWithIdAsSeparateRoot() throws Exception {
        // Nested objects WITH "id" field → SingularObjectRef (separate root)
        var jsonContent = """
            [
                {"id": 1, "name": "Alice", "profile": {"id": 101, "bio": "Developer"}},
                {"id": 2, "name": "Bob", "profile": {"id": 102, "bio": "Designer"}}
            ]
            """;

        var jsonFile = tempDir.resolve("users.json");
        Files.writeString(jsonFile, jsonContent);

        var dataSource = new JsonDataSource(jsonFile);
        var structure = dataSource.detectStructure();

        var userRoot = structure.roots().get("users");
        assertThat(userRoot.fields().get("profile")).isInstanceOf(DetectedField.SingularObjectRef.class);

        var profileRef = (DetectedField.SingularObjectRef) userRoot.fields().get("profile");
        assertThat(profileRef.targetRootName()).isEqualTo("users_profile");

        // Separate root created for profile
        assertThat(structure.roots()).containsKeys("users", "users_profile");
        var profileRoot = structure.roots().get("users_profile");
        assertThat(profileRoot.fields()).containsKeys("id", "bio");

        dataSource.close();
    }

    @Test
    void shouldDetectScalarArrays() throws Exception {
        var jsonContent = """
            [
                {"id": 1, "name": "Alice", "tags": ["admin", "user"]},
                {"id": 2, "name": "Bob", "tags": ["user"]}
            ]
            """;

        var jsonFile = tempDir.resolve("users.json");
        Files.writeString(jsonFile, jsonContent);

        var dataSource = new JsonDataSource(jsonFile);
        var structure = dataSource.detectStructure();

        var userRoot = structure.roots().get("users");
        assertThat(userRoot.fields().get("tags")).isInstanceOf(DetectedField.ScalarArray.class);

        dataSource.close();
    }

    @Test
    void shouldDetectObjectArraysWithoutIdAsCompositeCollection() throws Exception {
        // Array of objects WITHOUT "id" field → CompositeCollection (embedded)
        var jsonContent = """
            [
                {"id": 1, "name": "Order1", "items": [
                    {"sku": "A001", "qty": 2},
                    {"sku": "B002", "qty": 1}
                ]}
            ]
            """;

        var jsonFile = tempDir.resolve("orders.json");
        Files.writeString(jsonFile, jsonContent);

        var dataSource = new JsonDataSource(jsonFile);
        var structure = dataSource.detectStructure();

        var orderRoot = structure.roots().get("orders");
        assertThat(orderRoot.fields().get("items")).isInstanceOf(DetectedField.CompositeCollection.class);

        var itemsField = (DetectedField.CompositeCollection) orderRoot.fields().get("items");
        assertThat(itemsField.elementFields()).containsKeys("sku", "qty");

        // No separate root created
        assertThat(structure.roots()).containsOnlyKeys("orders");

        dataSource.close();
    }

    @Test
    void shouldDetectObjectArraysWithIdAsPluralObjectRef() throws Exception {
        // Array of objects WITH "id" field → PluralObjectRef (separate root)
        var jsonContent = """
            [
                {"id": 1, "name": "Order1", "items": [
                    {"id": 10, "sku": "A001", "qty": 2},
                    {"id": 11, "sku": "B002", "qty": 1}
                ]}
            ]
            """;

        var jsonFile = tempDir.resolve("orders.json");
        Files.writeString(jsonFile, jsonContent);

        var dataSource = new JsonDataSource(jsonFile);
        var structure = dataSource.detectStructure();

        var orderRoot = structure.roots().get("orders");
        assertThat(orderRoot.fields().get("items")).isInstanceOf(DetectedField.PluralObjectRef.class);

        var itemsRef = (DetectedField.PluralObjectRef) orderRoot.fields().get("items");
        assertThat(itemsRef.targetRootName()).isEqualTo("orders_item");

        // Separate root created
        assertThat(structure.roots()).containsKeys("orders", "orders_item");
        var itemRoot = structure.roots().get("orders_item");
        assertThat(itemRoot.isPrimary()).isFalse();
        assertThat(itemRoot.parentRootName()).isEqualTo("orders");
        assertThat(itemRoot.fields()).containsKeys("id", "sku", "qty");

        dataSource.close();
    }

    @Test
    void shouldForceCompositeWithOverride() throws Exception {
        // Force objects WITH "id" to be treated as composite via converter override
        var jsonContent = """
            [
                {"id": 1, "profile": {"id": 101, "bio": "Developer"}}
            ]
            """;

        var jsonFile = tempDir.resolve("users.json");
        Files.writeString(jsonFile, jsonContent);

        var dataSource = new JsonDataSource(jsonFile);
        var structure = dataSource.detectStructure();

        // Raw structure detects profile as separate root (has "id")
        assertThat(structure.roots().get("users").fields().get("profile"))
            .isInstanceOf(DetectedField.SingularObjectRef.class);

        // Apply ForceComposite override via converter
        // Note: ForceComposite on already-detected ObjectRef is a TODO for now
        // The override works best when the field is already a Composite
        dataSource.close();
    }

    @Test
    void shouldForceSeparateRootWithOverride() throws Exception {
        // Force objects WITHOUT "id" to be treated as separate root via converter override
        var jsonContent = """
            [
                {"id": 1, "address": {"city": "NYC", "zip": "10001"}}
            ]
            """;

        var jsonFile = tempDir.resolve("users.json");
        Files.writeString(jsonFile, jsonContent);

        List<HierarchicalOverride> overrides = List.of(
            new HierarchicalOverride.ForceSeparateRoot("address", HierarchicalOverride.IdStrategy.AutoGenerate.INSTANCE)
        );

        var dataSource = new JsonDataSource(jsonFile);
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
    void shouldFlattenToMapFormat() throws Exception {
        var jsonContent = """
            [
                {"id": 1, "name": "Alice", "address": {"city": "NYC", "zip": "10001"}, "tags": ["admin", "user"]}
            ]
            """;

        var jsonFile = tempDir.resolve("users.json");
        Files.writeString(jsonFile, jsonContent);

        var dataSource = new JsonDataSource(jsonFile);

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
    void shouldApplyDataTypeOverride() throws Exception {
        var jsonContent = """
            [
                {"id": "abc123", "status": "active"},
                {"id": "def456", "status": "inactive"}
            ]
            """;

        var jsonFile = tempDir.resolve("items.json");
        Files.writeString(jsonFile, jsonContent);

        List<HierarchicalOverride> overrides = List.of(
            new HierarchicalOverride.DataTypeOverride("status", new CategorcialType(new String[]{"active", "inactive", "pending"}))
        );

        var dataSource = new JsonDataSource(jsonFile);
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
        var jsonContent = """
            [
                {"id": 1, "tags": ["a", "b", "c"]}
            ]
            """;

        var jsonFile = tempDir.resolve("items.json");
        Files.writeString(jsonFile, jsonContent);

        var dataSource = new JsonDataSource(jsonFile);
        var rows = dataSource.stream().toList();

        assertThat(rows.getFirst().get("tags")).asInstanceOf(LIST).containsExactly("a", "b", "c");

        dataSource.close();
    }

    @Test
    void shouldStreamLargeFiles() throws Exception {
        var sb = new StringBuilder("[\n");
        for (int i = 0; i < 1000; i++) {
            if (i > 0) {
                sb.append(",\n");
            }
            sb.append(String.format("  {\"id\": %d, \"value\": \"item%d\"}", i, i));
        }
        sb.append("\n]");

        var jsonFile = tempDir.resolve("large.json");
        Files.writeString(jsonFile, sb.toString());

        var dataSource = new JsonDataSource(jsonFile);
        var count = dataSource.stream().count();

        assertThat(count).isEqualTo(1000);

        dataSource.close();
    }

    @Test
    void shouldDeriveRootNameFromFileName() throws Exception {
        var jsonContent = "[{\"x\": 1}]";

        var jsonFile = tempDir.resolve("my_entities.json");
        Files.writeString(jsonFile, jsonContent);

        var dataSource = new JsonDataSource(jsonFile);
        assertThat(dataSource.getRootName()).isEqualTo("my_entities");

        dataSource.close();
    }

    @Test
    void shouldPreserveNativeTypes() throws Exception {
        var jsonContent = """
            [
                {"intVal": 42, "floatVal": 3.14, "boolVal": true, "strVal": "text"}
            ]
            """;

        var jsonFile = tempDir.resolve("types.json");
        Files.writeString(jsonFile, jsonContent);

        var dataSource = new JsonDataSource(jsonFile);
        var rows = dataSource.stream().toList();
        var row = rows.getFirst();

        assertThat(row.get("intVal")).asInstanceOf(LONG).isEqualTo(42L);
        assertThat(row.get("floatVal")).asInstanceOf(DOUBLE).isEqualTo(3.14);
        assertThat(row.get("boolVal")).asInstanceOf(BOOLEAN).isTrue();
        assertThat(row.get("strVal")).asInstanceOf(STRING).isEqualTo("text");

        dataSource.close();
    }

    // ========== Converter Override Tests ==========

    @Test
    void shouldForceSeparateRootOnCompositeCollection() throws Exception {
        // Array of objects WITHOUT "id" but forced to be separate root via converter
        var jsonContent = """
            [
                {"id": 1, "tags": [
                    {"name": "tag1", "color": "red"},
                    {"name": "tag2", "color": "blue"}
                ]}
            ]
            """;

        var jsonFile = tempDir.resolve("items.json");
        Files.writeString(jsonFile, jsonContent);

        List<HierarchicalOverride> overrides = List.of(
            new HierarchicalOverride.ForceSeparateRoot("tags", HierarchicalOverride.IdStrategy.AutoGenerate.INSTANCE)
        );

        var dataSource = new JsonDataSource(jsonFile);
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
        var jsonContent = """
            [
                {"id": 1, "settings": {"key": "theme", "value": "dark"}}
            ]
            """;

        var jsonFile = tempDir.resolve("users.json");
        Files.writeString(jsonFile, jsonContent);

        List<HierarchicalOverride> overrides = List.of(
            new HierarchicalOverride.ForceSeparateRoot(
                "settings",
                new HierarchicalOverride.IdStrategy.UseField("key", new DataType.StringType())
            )
        );

        var dataSource = new JsonDataSource(jsonFile);
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
        var jsonContent = """
            [
                {"id": 1, "address": {"city": "NYC", "type": "home"}}
            ]
            """;

        var jsonFile = tempDir.resolve("users.json");
        Files.writeString(jsonFile, jsonContent);

        List<HierarchicalOverride> overrides = List.of(
            new HierarchicalOverride.DataTypeOverride(
                "address.type",
                new CategorcialType(new String[]{"home", "work", "other"})
            )
        );

        var dataSource = new JsonDataSource(jsonFile);
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
        var jsonContent = """
            [
                {
                    "id": 1,
                    "status": "active",
                    "settings": {"theme": "dark"}
                }
            ]
            """;

        var jsonFile = tempDir.resolve("users.json");
        Files.writeString(jsonFile, jsonContent);

        List<HierarchicalOverride> overrides = List.of(
            // Override data type
            new HierarchicalOverride.DataTypeOverride("status", new CategorcialType(new String[]{"active", "inactive"})),
            // Force settings (no ID) to be separate root
            new HierarchicalOverride.ForceSeparateRoot("settings", HierarchicalOverride.IdStrategy.AutoGenerate.INSTANCE)
        );

        var dataSource = new JsonDataSource(jsonFile);
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
        var jsonContent = """
            [
                {
                    "id": 1,
                    "level1": {
                        "level2": {
                            "level3": {
                                "value": "deep"
                            }
                        }
                    }
                }
            ]
            """;

        var jsonFile = tempDir.resolve("deep.json");
        Files.writeString(jsonFile, jsonContent);

        var dataSource = new JsonDataSource(jsonFile);
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
        var jsonContent = """
            [
                {
                    "id": 1,
                    "scalarArray": [1, 2, 3],
                    "objectArrayNoId": [{"x": 1}, {"x": 2}],
                    "objectArrayWithId": [{"id": 10, "y": "a"}, {"id": 11, "y": "b"}]
                }
            ]
            """;

        var jsonFile = tempDir.resolve("mixed.json");
        Files.writeString(jsonFile, jsonContent);

        var dataSource = new JsonDataSource(jsonFile);
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
        var jsonContent = """
            [
                {"id": 1, "tags": [], "items": []}
            ]
            """;

        var jsonFile = tempDir.resolve("empty.json");
        Files.writeString(jsonFile, jsonContent);

        var dataSource = new JsonDataSource(jsonFile);
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
    @SuppressWarnings("DataFlowIssue")
    void shouldHandleNullFieldsInObjects() throws Exception {
        var jsonContent = """
            [
                {"id": 1, "name": "Alice", "email": null},
                {"id": 2, "name": "Bob", "email": "bob@test.com"}
            ]
            """;

        var jsonFile = tempDir.resolve("nulls.json");
        Files.writeString(jsonFile, jsonContent);

        var dataSource = new JsonDataSource(jsonFile);
        var rows = dataSource.stream().toList();

        assertThat(rows.get(0).get("email")).isNull();
        assertThat(rows.get(1).get("email")).asInstanceOf(STRING).isEqualTo("bob@test.com");

        dataSource.close();
    }

    @Test
    void shouldDetectNumericTypesCorrectly() throws Exception {
        var jsonContent = """
            [
                {"id": 1, "intField": 42, "longField": 9999999999, "doubleField": 3.14159}
            ]
            """;

        var jsonFile = tempDir.resolve("numbers.json");
        Files.writeString(jsonFile, jsonContent);

        var dataSource = new JsonDataSource(jsonFile);
        var structure = dataSource.detectStructure();

        var root = structure.roots().get("numbers");
        assertThat(root.fields().get("intField")).isInstanceOf(DetectedField.Scalar.class);
        assertThat(root.fields().get("longField")).isInstanceOf(DetectedField.Scalar.class);
        assertThat(root.fields().get("doubleField")).isInstanceOf(DetectedField.Scalar.class);

        var rows = dataSource.stream().toList();
        assertThat(rows.getFirst().get("intField")).asInstanceOf(LONG).isEqualTo(42L);
        assertThat(rows.getFirst().get("longField")).asInstanceOf(LONG).isEqualTo(9999999999L);
        assertThat(rows.getFirst().get("doubleField")).asInstanceOf(DOUBLE).isEqualTo(3.14159);

        dataSource.close();
    }

    @SuppressWarnings("DataFlowIssue")
    @Test
    void shouldOverrideNumericPrecision() throws Exception {
        var jsonContent = """
            [
                {"id": 1, "price": "123.45"}
            ]
            """;

        var jsonFile = tempDir.resolve("prices.json");
        Files.writeString(jsonFile, jsonContent);

        List<HierarchicalOverride> overrides = List.of(
            new HierarchicalOverride.DataTypeOverride("price", new DataType.NumericType(10, 2))
        );

        var dataSource = new JsonDataSource(jsonFile);
        var structure = dataSource.detectStructure();

        // Convert with override
        var schema = converter.convert(structure, "prices", Set.of(), overrides);

        var priceAttr = (DetectedAttribute.Basic) schema.roots().get("prices").attributes().get("price");
        assertThat(priceAttr.dataType()).isInstanceOf(DataType.NumericType.class);
        var numericType = (DataType.NumericType) priceAttr.dataType();
        assertThat(numericType.precision()).isEqualTo(10);
        assertThat(numericType.scale()).isEqualTo(2);

        dataSource.close();
    }

    @Test
    void shouldHandleSingularization() throws Exception {
        // Test various plural forms
        var jsonContent = """
            [
                {"id": 1,
                 "categories": [{"id": 10}],
                 "boxes": [{"id": 20}],
                 "statuses": [{"id": 30}],
                 "children": [{"id": 40}]
                }
            ]
            """;

        var jsonFile = tempDir.resolve("test.json");
        Files.writeString(jsonFile, jsonContent);

        var dataSource = new JsonDataSource(jsonFile);
        var structure = dataSource.detectStructure();

        // Check singularized root names
        assertThat(structure.roots()).containsKeys(
            "test",
            "test_category",    // categories → category
            "test_box",         // boxes → box (special es ending)
            "test_status",      // statuses → status (special es ending)
            "test_child"        // children stays as is (irregular)
        );

        dataSource.close();
    }

    @Test
    void shouldRespectColumnOrderInFlattenedOutput() throws Exception {
        var jsonContent = """
            [
                {"id": 1, "z_field": "z", "a_field": "a", "m_field": "m"}
            ]
            """;

        var jsonFile = tempDir.resolve("order.json");
        Files.writeString(jsonFile, jsonContent);

        var dataSource = new JsonDataSource(jsonFile);
        var columnNames = dataSource.getColumnNames();

        // Columns should be in the order they appear in JSON
        assertThat(columnNames).containsExactly("id", "z_field", "a_field", "m_field");

        dataSource.close();
    }

    @Test
    void shouldHandleSpecialCharactersInFieldNames() throws Exception {
        var jsonContent = """
            [
                {"id": 1, "field_with_underscore": "a", "fieldWithCamelCase": "b", "field-with-dash": "c"}
            ]
            """;

        var jsonFile = tempDir.resolve("special.json");
        Files.writeString(jsonFile, jsonContent);

        var dataSource = new JsonDataSource(jsonFile);
        var structure = dataSource.detectStructure();

        var root = structure.roots().get("special");
        assertThat(root.fields()).containsKeys("id", "field_with_underscore", "fieldWithCamelCase", "field-with-dash");

        dataSource.close();
    }

    @Test
    void shouldFlattenPeakSeasonMonthsArrayAsListOfNumbers() throws Exception {
        var jsonContent = """
            [
              {"id": 1, "peak_season_months": [1, 12, 2]},
              {"id": 2, "peak_season_months": []}
            ]
            """;

        var jsonFile = tempDir.resolve("products.json");
        Files.writeString(jsonFile, jsonContent);

        var dataSource = new JsonDataSource(jsonFile);
        var rows = dataSource.stream().toList();

        assertThat(rows)
            .filteredOn(row -> ((Number) row.get("id")).longValue() == 1L)
            .singleElement()
            .extracting(row -> row.get("peak_season_months"))
            .asInstanceOf(LIST)
            .containsExactly(1L, 12L, 2L);

        assertThat(rows)
            .filteredOn(row -> ((Number) row.get("id")).longValue() == 2L)
            .singleElement()
            .extracting(row -> row.get("peak_season_months"))
            .asInstanceOf(LIST)
            .isEmpty();

        dataSource.close();
    }
}
