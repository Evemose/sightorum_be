package com.rorm.dataimport.pipeline;

import com.rorm.metamodel.*;
import com.rorm.metamodel.DataType.CategorcialType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.STRING;

@DisplayName("SchemaGenerator")
class SchemaGeneratorTest {

    private final SchemaGenerator generator = new SchemaGenerator();

    @Test
    @DisplayName("generates CREATE TABLE for root with basic attributes")
    void generateCreateTableBasic() {
        var root = new Root("users", List.of(
            new BasicAttribute("name", new AttributeLocation("users", "name"), new DataType.StringType()),
            new BasicAttribute("age", new AttributeLocation("users", "age"), new DataType.NumericType(3, 0))
        ), IdDescriptor.longId("users"));

        var ddl = generator.generateAllTablesDdl("test_schema", new ModelSpace(Set.of(root)));

        assertThat(ddl).singleElement(STRING)
            .contains("CREATE TABLE \"test_schema\".\"users\"")
            .contains("\"id\" BIGINT PRIMARY KEY")
            .contains("\"name\" TEXT")
            .contains("\"age\" SMALLINT");
    }

    @Test
    @DisplayName("maps numeric types correctly based on precision and scale")
    void mapNumericTypes() {
        var root = new Root("numbers", List.of(
            new BasicAttribute("small", new AttributeLocation("numbers", "small"), new DataType.NumericType(3, 0)),
            new BasicAttribute("medium", new AttributeLocation("numbers", "medium"), new DataType.NumericType(8, 0)),
            new BasicAttribute("large", new AttributeLocation("numbers", "large"), new DataType.NumericType(15, 0)),
            new BasicAttribute("decimal", new AttributeLocation("numbers", "decimal"), new DataType.NumericType(10, 2))
        ), IdDescriptor.longId("numbers"));

        var ddl = generator.generateAllTablesDdl("test_schema", new ModelSpace(Set.of(root)));

        assertThat(ddl).singleElement(STRING)
            .contains("\"small\" SMALLINT")
            .contains("\"medium\" INTEGER")
            .contains("\"large\" BIGINT")
            .contains("\"decimal\" NUMERIC(10, 2)");
    }

    @Test
    @DisplayName("generates columns for composite attributes")
    void generateCompositeAttributes() {
        var root = new Root("users", List.of(
            new CompositeAttribute("address", Set.of(
                new BasicAttribute("street", new AttributeLocation("users", "address_street"), new DataType.StringType()),
                new BasicAttribute("city", new AttributeLocation("users", "address_city"), new DataType.StringType())
            ))
        ), IdDescriptor.longId("users"));

        var ddl = generator.generateAllTablesDdl("test_schema", new ModelSpace(Set.of(root)));

        assertThat(ddl).singleElement(STRING)
            .contains("\"address_street\" TEXT")
            .contains("\"address_city\" TEXT");
    }

    @Test
    @DisplayName("generates array column for basic collection attributes")
    void generateBasicCollectionAttributeColumn() {
        var root = new Root("products", List.of(
            new CollectionAttribute(
                "peakSeasonMonths",
                "products",
                new CollectionAttribute.BasicElement(
                    new AttributeLocation("products", "peak_season_months"),
                    new DataType.NumericType(19, 0)
                )
            )
        ), IdDescriptor.longId("products"));

        var ddl = generator.generateAllTablesDdl("test_schema", new ModelSpace(Set.of(root)));

        assertThat(ddl).singleElement(STRING)
            .contains("\"peak_season_months\" BIGINT[]")
            .doesNotContain("\"peak_season_months\" BIGINT,");
    }

    @Test
    @DisplayName("generates foreign key column for singular reference with SameTableColumn")
    void generateSingularReferenceColumn() {
        var targetRoot = new Root("orders", List.of(), IdDescriptor.longId("orders"));
        var root = new Root("users", List.of(
            new SingularReferenceAttribute(
                "order",
                targetRoot,
                new ReferenceAttribute.SameTableColumn("order_id")
            )
        ), IdDescriptor.longId("users"));

        var ddl = generator.generateAllTablesDdl("test_schema", new ModelSpace(Set.of(root, targetRoot)));

        assertThat(ddl)
            .filteredOn(ddlStmt -> ddlStmt.toLowerCase().contains("\"test_schema\".\"users\""))
            .singleElement(STRING)
            .contains("\"order_id\" BIGINT");
    }

    @Test
    @DisplayName("generates foreign key column for singular reference with SameTableColumn")
    void generateSingularReferenceInverseColumn() {
        var targetRoot = new Root("orders", new ArrayList<>(), IdDescriptor.longId("orders"));
        var root = new Root("users", List.of(
            new SingularReferenceAttribute(
                "order",
                targetRoot,
                new ReferenceAttribute.InverseRootTableColumn("order_id")
            )
        ), IdDescriptor.longId("users"));
        targetRoot.attributes().add(new SingularReferenceAttribute(
            "user",
            root,
            new ReferenceAttribute.SameTableColumn("user_id")
        ));

        var ddl = generator.generateAllTablesDdl("test_schema", new ModelSpace(Set.of(root, targetRoot)));

        assertThat(ddl)
            .filteredOn(ddlStmt -> ddlStmt.toLowerCase().contains("\"test_schema\".\"orders\""))
            .singleElement(STRING)
            .contains("\"user_id\" BIGINT");

        assertThat(ddl)
            .filteredOn(ddlStmt -> ddlStmt.toLowerCase().contains("\"test_schema\".\"users\""))
            .singleElement(STRING)
            .doesNotContain("\"order_id\" BIGINT");
    }

    @Test
    @DisplayName("generates join table for reference with JoinTableMapping")
    void generateJoinTable() {
        var targetRoot = new Root("roles", List.of(), IdDescriptor.longId("roles"));
        var ownerRoot = new Root("users", List.of(
            new PluralReferenceAttribute(
                "roles",
                targetRoot,
                new ReferenceAttribute.JoinTableMapping(
                    new AttributeLocation("user_roles", "user_id"),
                    "role_id"
                )
            )
        ), IdDescriptor.longId("users"));

        var modelSpace = new ModelSpace(Set.of(ownerRoot, targetRoot));
        var ddls = generator.generateAllTablesDdl("test_schema", modelSpace);

        var joinTableDdl = ddls.stream()
            .filter(ddl -> ddl.contains("user_roles"))
            .findFirst();

        assertThat(joinTableDdl)
            .isPresent()
            .get(STRING)
            .contains("CREATE TABLE \"test_schema\".\"user_roles\"")
            .contains("\"user_id\" BIGINT")
            .contains("\"role_id\" BIGINT")
            .contains("PRIMARY KEY (\"user_id\", \"role_id\")");
    }

    @Test
    @DisplayName("generates all tables including join tables")
    void generateAllTables() {
        var roleRoot = new Root("roles", List.of(
            new BasicAttribute("name", new AttributeLocation("roles", "name"), new DataType.StringType())
        ), IdDescriptor.longId("roles"));

        var userRoot = new Root("users", List.of(
            new BasicAttribute("name", new AttributeLocation("users", "name"), new DataType.StringType()),
            new PluralReferenceAttribute(
                "roles",
                roleRoot,
                new ReferenceAttribute.JoinTableMapping(
                    new AttributeLocation("user_roles", "user_id"),
                    "role_id"
                )
            )
        ), IdDescriptor.longId("users"));

        var modelSpace = new ModelSpace(Set.of(userRoot, roleRoot));
        var ddls = generator.generateAllTablesDdl("test_schema", modelSpace);

        assertThat(ddls).hasSize(3)
            .anyMatch(ddl -> ddl.contains("CREATE TABLE \"test_schema\".\"users\""))
            .anyMatch(ddl -> ddl.contains("CREATE TABLE \"test_schema\".\"roles\""))
            .anyMatch(ddl -> ddl.contains("CREATE TABLE \"test_schema\".\"user_roles\""));
    }

    @Test
    @DisplayName("maps all DataType variants to SQL types")
    void mapAllDataTypes() {
        var root = new Root("types", List.of(
            new BasicAttribute("text_col", new AttributeLocation("types", "text_col"), new DataType.StringType()),
            new BasicAttribute("bool_col", new AttributeLocation("types", "bool_col"), new DataType.BooleanType()),
            new BasicAttribute("date_col", new AttributeLocation("types", "date_col"), new DataType.DateType()),
            new BasicAttribute("time_col", new AttributeLocation("types", "time_col"), new DataType.TimeType()),
            new BasicAttribute("datetime_col", new AttributeLocation("types", "datetime_col"), new DataType.DateTimeType()),
            new BasicAttribute("timezone_col", new AttributeLocation("types", "timezone_col"), new DataType.TimezoneType()),
            new BasicAttribute("day_col", new AttributeLocation("types", "day_col"), new DataType.DayOfWeekType()),
            new BasicAttribute("enum_col", new AttributeLocation("types", "enum_col"), new CategorcialType(new String[]{"A", "B"}))
        ), IdDescriptor.longId("types"));

        var ddl = generator.generateAllTablesDdl("test_schema", new ModelSpace(Set.of(root)));

        assertThat(ddl).singleElement(STRING)
            .contains("\"text_col\" TEXT")
            .contains("\"bool_col\" BOOLEAN")
            .contains("\"date_col\" DATE")
            .contains("\"time_col\" TIME")
            .contains("\"datetime_col\" TIMESTAMP WITH TIME ZONE")
            .contains("\"timezone_col\" TEXT")
            .contains("\"day_col\" TEXT")
            .contains("\"enum_col\" TEXT");
    }

    @Test
    @DisplayName("generates VARCHAR primary key for string ID type")
    void generateStringIdPrimaryKey() {
        var root = new Root("products", List.of(
            new BasicAttribute("name", new AttributeLocation("products", "name"), new DataType.StringType())
        ), IdDescriptor.stringId("products"));

        var ddl = generator.generateAllTablesDdl("test_schema", new ModelSpace(Set.of(root)));

        assertThat(ddl).singleElement(STRING)
            .contains("\"id\" VARCHAR(255) PRIMARY KEY");
    }

    @Test
    @DisplayName("generates FK type matching target root ID type")
    void generateForeignKeyMatchingTargetIdType() {
        var targetRoot = new Root("categories", List.of(), IdDescriptor.stringId("categories"));
        var ownerRoot = new Root("products", List.of(
            new SingularReferenceAttribute(
                "category",
                targetRoot,
                new ReferenceAttribute.SameTableColumn("category_id")
            )
        ), IdDescriptor.longId("products"));

        var ddl = generator.generateAllTablesDdl("test_schema", new ModelSpace(Set.of(ownerRoot, targetRoot)));

        var productsDdl = ddl.stream()
            .filter(d -> d.contains("\"test_schema\".\"products\""))
            .findFirst();

        assertThat(productsDdl).isPresent()
            .get(STRING)
            .contains("\"category_id\" VARCHAR(255)");
    }

    @Test
    @DisplayName("generates join table with mixed ID types")
    void generateJoinTableWithMixedIdTypes() {
        var roleRoot = new Root("roles", List.of(), IdDescriptor.stringId("roles"));
        var userRoot = new Root("users", List.of(
            new PluralReferenceAttribute(
                "roles",
                roleRoot,
                new ReferenceAttribute.JoinTableMapping(
                    new AttributeLocation("user_roles", "user_id"),
                    "role_id"
                )
            )
        ), IdDescriptor.longId("users"));

        var ddl = generator.generateAllTablesDdl("test_schema", new ModelSpace(Set.of(userRoot, roleRoot)));

        var joinTableDdl = ddl.stream()
            .filter(d -> d.contains("user_roles"))
            .findFirst();

        assertThat(joinTableDdl).isPresent()
            .get(STRING)
            .contains("\"user_id\" BIGINT")
            .contains("\"role_id\" VARCHAR(255)");
    }
}
