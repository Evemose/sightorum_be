package com.rorm.dataimport.pipeline;

import com.rorm.metamodel.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
        ));

        var ddl = generator.generateAllTablesDdl("test_schema", new ModelSpace(Set.of(root)));

        assertThat(ddl).singleElement(STRING)
            .contains("CREATE TABLE test_schema.users")
            .contains("id BIGINT PRIMARY KEY")
            .contains("name TEXT")
            .contains("age SMALLINT");
    }

    @Test
    @DisplayName("maps numeric types correctly based on precision and scale")
    void mapNumericTypes() {
        var root = new Root("numbers", List.of(
            new BasicAttribute("small", new AttributeLocation("numbers", "small"), new DataType.NumericType(3, 0)),
            new BasicAttribute("medium", new AttributeLocation("numbers", "medium"), new DataType.NumericType(8, 0)),
            new BasicAttribute("large", new AttributeLocation("numbers", "large"), new DataType.NumericType(15, 0)),
            new BasicAttribute("decimal", new AttributeLocation("numbers", "decimal"), new DataType.NumericType(10, 2))
        ));

        var ddl = generator.generateAllTablesDdl("test_schema", new ModelSpace(Set.of(root)));

        assertThat(ddl).singleElement(STRING)
            .contains("small SMALLINT")
            .contains("medium INTEGER")
            .contains("large BIGINT")
            .contains("decimal NUMERIC(10, 2)");
    }

    @Test
    @DisplayName("generates columns for composite attributes")
    void generateCompositeAttributes() {
        var root = new Root("users", List.of(
            new CompositeAttribute("address", Set.of(
                new BasicAttribute("street", new AttributeLocation("users", "address_street"), new DataType.StringType()),
                new BasicAttribute("city", new AttributeLocation("users", "address_city"), new DataType.StringType())
            ))
        ));

        var ddl = generator.generateAllTablesDdl("test_schema", new ModelSpace(Set.of(root)));

        assertThat(ddl).singleElement(STRING)
            .contains("address_street TEXT")
            .contains("address_city TEXT");
    }

    @Test
    @DisplayName("generates foreign key column for singular reference with InverseRootTableColumn")
    void generateSingularReferenceColumn() {
        var targetRoot = new Root("orders", List.of());
        var root = new Root("users", List.of(
            new SingularReferenceAttribute(
                "order",
                targetRoot,
                new ReferenceAttribute.InverseRootTableColumn("order_id")
            )
        ));

        var ddl = generator.generateAllTablesDdl("test_schema", new ModelSpace(Set.of(root, targetRoot)));

        assertThat(ddl)
            .filteredOn(ddlStmt -> ddlStmt.toLowerCase().contains("create table test_schema.users"))
            .singleElement(STRING)
            .contains("order_id BIGINT");
    }

    @Test
    @DisplayName("generates join table for reference with JoinTableMapping")
    void generateJoinTable() {
        var targetRoot = new Root("roles", List.of());
        var ownerRoot = new Root("users", List.of(
            new PluralReferenceAttribute(
                "roles",
                targetRoot,
                new ReferenceAttribute.JoinTableMapping(
                    new AttributeLocation("user_roles", "user_id"),
                    "role_id"
                )
            )
        ));

        var modelSpace = new ModelSpace(Set.of(ownerRoot, targetRoot));
        var ddls = generator.generateAllTablesDdl("test_schema", modelSpace);

        var joinTableDdl = ddls.stream()
            .filter(ddl -> ddl.contains("user_roles"))
            .findFirst();

        assertThat(joinTableDdl).isPresent();
        assertThat(joinTableDdl.get()).contains("CREATE TABLE test_schema.user_roles");
        assertThat(joinTableDdl.get()).contains("user_id BIGINT");
        assertThat(joinTableDdl.get()).contains("role_id BIGINT");
        assertThat(joinTableDdl.get()).contains("PRIMARY KEY (user_id, role_id)");
    }

    @Test
    @DisplayName("generates all tables including join tables")
    void generateAllTables() {
        var roleRoot = new Root("roles", List.of(
            new BasicAttribute("name", new AttributeLocation("roles", "name"), new DataType.StringType())
        ));

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
        ));

        var modelSpace = new ModelSpace(Set.of(userRoot, roleRoot));
        var ddls = generator.generateAllTablesDdl("test_schema", modelSpace);

        assertThat(ddls).hasSize(3)
            .anyMatch(ddl -> ddl.contains("CREATE TABLE test_schema.users"))
            .anyMatch(ddl -> ddl.contains("CREATE TABLE test_schema.roles"))
            .anyMatch(ddl -> ddl.contains("CREATE TABLE test_schema.user_roles"));
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
            new BasicAttribute("enum_col", new AttributeLocation("types", "enum_col"), new DataType.EnumType(new String[]{"A", "B"}))
        ));

        var ddl = generator.generateAllTablesDdl("test_schema", new ModelSpace(Set.of(root)));

        assertThat(ddl).singleElement(STRING)
            .contains("text_col TEXT")
            .contains("bool_col BOOLEAN")
            .contains("date_col DATE")
            .contains("time_col TIME")
            .contains("datetime_col TIMESTAMP WITH TIME ZONE")
            .contains("timezone_col TEXT")
            .contains("day_col TEXT")
            .contains("enum_col TEXT");
    }
}
