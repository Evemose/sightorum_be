package com.rorm.dataimport.pipeline;

import com.rorm.metamodel.*;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Generates SQL schema DDL from metamodel.
 */
@Component
class SchemaGenerator {

    /**
     * Generates all CREATE TABLE statements for roots and join tables.
     * Uses proper SQL types based on DataType.
     */
    public List<String> generateAllTablesDdl(String schemaName, ModelSpace modelSpace) {
        var ddlStatements = new ArrayList<String>();

        // Generate root tables
        for (var root : modelSpace.roots()) {
            ddlStatements.add(generateCreateTableDdl(schemaName, root));
        }

        // Generate join tables
        for (var root : modelSpace.roots()) {
            ddlStatements.addAll(generateJoinTablesDdl(schemaName, root));
        }

        return ddlStatements;
    }

    private String generateCreateTableDdl(String schemaName, Root root) {
        var qualifiedTableName = "%s.%s".formatted(schemaName, root.primaryTableName());
        var columnDefinitions = new ArrayList<String>();

        // Check if any attribute already defines an "id" column
        var hasIdColumn = root.attributes().stream()
            .anyMatch(attr -> attr instanceof BasicAttribute basic &&
                              basic.location().column().equalsIgnoreCase("id"));

        // Add id column as primary key only if not already defined
        if (!hasIdColumn) {
            columnDefinitions.add("id BIGINT PRIMARY KEY");
        }

        // Add columns for each attribute
        for (var attribute : root.attributes()) {
            columnDefinitions.addAll(generateColumnDefinitions(attribute, hasIdColumn));
        }

        return "CREATE TABLE %s (%s)".formatted(
            qualifiedTableName,
            String.join(", ", columnDefinitions)
        );
    }

    private List<String> generateColumnDefinitions(Attribute attribute, boolean hasExplicitIdColumn) {
        return switch (attribute) {
            case BasicAttribute basic -> {
                var columnDef = "%s %s".formatted(
                    basic.location().column(),
                    mapDataTypeToSql(basic.dataType())
                );
                // If this is an id column and we have an explicit id, add PRIMARY KEY constraint
                if (hasExplicitIdColumn && basic.location().column().equalsIgnoreCase("id")) {
                    columnDef += " PRIMARY KEY";
                }
                yield List.of(columnDef);
            }
            case CollectionAttribute collection -> switch (collection.elementType()) {
                case CollectionAttribute.BasicElement basicElement -> List.of(
                    "%s %s".formatted(
                        basicElement.location().column(),
                        mapDataTypeToSql(basicElement.dataType())
                    )
                );
                case CollectionAttribute.CompositeElement _ ->
                    // Composite elements in collections don't create simple columns
                    List.of();
            };
            case CompositeAttribute composite -> {
                var columns = new ArrayList<String>();
                for (var subAttribute : composite.attributes()) {
                    columns.addAll(generateColumnDefinitions(subAttribute, hasExplicitIdColumn));
                }
                yield columns;
            }
            case SingularReferenceAttribute ref -> switch (ref.mappingStrategy()) {
                case ReferenceAttribute.InverseRootTableColumn(var columnName) -> List.of(
                    "%s BIGINT".formatted(columnName)
                );
                case ReferenceAttribute.JoinTableMapping _ ->
                    // Join tables are separate, not columns
                    List.of();
            };
            case PluralReferenceAttribute _ ->
                // Plural references don't create columns in this table
                List.of();
        };
    }

    /**
     * Maps DataType to SQL type.
     */
    private String mapDataTypeToSql(DataType dataType) {
        return switch (dataType) {
            case DataType.NumericType numeric -> {
                if (numeric.scale() > 0) {
                    yield "NUMERIC(%d, %d)".formatted(numeric.precision(), numeric.scale());
                } else {
                    // For integers, use appropriate type based on precision
                    if (numeric.precision() <= 4) {
                        yield "SMALLINT";
                    } else if (numeric.precision() <= 9) {
                        yield "INTEGER";
                    } else {
                        yield "BIGINT";
                    }
                }
            }
            case DataType.StringType _ -> "TEXT";
            case DataType.BooleanType _ -> "BOOLEAN";
            case DataType.DateType _ -> "DATE";
            case DataType.TimeType _ -> "TIME";
            case DataType.DateTimeType _ -> "TIMESTAMP WITH TIME ZONE";
            case DataType.TimezoneType _ -> "TEXT"; // Store as ISO string
            case DataType.DayOfWeekType _ -> "TEXT"; // Store as day name
            case DataType.EnumType _ -> "TEXT"; // Store as enum value string
            case DataType.ListType listType -> {
                // For list types, we store as TEXT array or JSON depending on element type
                // For now, use TEXT[] for simple types
                var elementSql = mapDataTypeToSql(listType.elementType());
                yield elementSql + "[]";
            }
        };
    }

    /**
     * Generates CREATE TABLE statements for join tables used by this root.
     */
    private List<String> generateJoinTablesDdl(String schemaName, Root root) {
        var joinTableDdls = new ArrayList<String>();
        collectJoinTablesRecursive(root.attributes(), schemaName, root, joinTableDdls);
        return joinTableDdls;
    }

    private void collectJoinTablesRecursive(
        List<Attribute> attributes,
        String schemaName,
        Root ownerRoot,
        List<String> joinTableDdls
    ) {
        for (var attribute : attributes) {
            switch (attribute) {
                case SingularReferenceAttribute(_, var targetRoot, ReferenceAttribute.JoinTableMapping mapping) ->
                    joinTableDdls.add(generateJoinTableDdl(schemaName, ownerRoot, targetRoot, mapping));
                case PluralReferenceAttribute(_, var targetRoot, ReferenceAttribute.JoinTableMapping mapping) ->
                    joinTableDdls.add(generateJoinTableDdl(schemaName, ownerRoot, targetRoot, mapping));
                case CompositeAttribute composite ->
                    collectJoinTablesRecursive(composite.attributes().stream().toList(), schemaName, ownerRoot, joinTableDdls);
                default -> {
                    // No join tables for other attribute types
                }
            }
        }
    }

    private String generateJoinTableDdl(
        String schemaName,
        Root ownerRoot,
        Root targetRoot,
        ReferenceAttribute.JoinTableMapping mapping
    ) {
        var joinTableName = "%s.%s".formatted(schemaName, mapping.joinColumnLocation().table());
        var ownerColumnName = mapping.joinColumnLocation().column();
        var targetColumnName = mapping.inverseJoinColumnName();

        return "CREATE TABLE %s (%s BIGINT, %s BIGINT, PRIMARY KEY (%s, %s))".formatted(
            joinTableName,
            ownerColumnName,
            targetColumnName,
            ownerColumnName,
            targetColumnName
        );
    }
}
