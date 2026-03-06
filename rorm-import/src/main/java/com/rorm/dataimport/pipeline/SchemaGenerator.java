package com.rorm.dataimport.pipeline;

import com.rorm.metamodel.*;
import com.rorm.metamodel.ReferenceAttribute.SameTableColumn;

import java.util.ArrayList;
import java.util.List;

/**
 * Generates SQL schema DDL from metamodel.
 */
@org.springframework.stereotype.Component
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
        var qualifiedTableName = quoteIdentifier(schemaName) + "." + quoteIdentifier(root.primaryTableName());
        var columnDefinitions = new ArrayList<String>();

        var idDescriptor = root.idDescriptor();
        columnDefinitions.add(quoteIdentifier(idDescriptor.columnName()) + " " +
                              mapIdTypeToSql(idDescriptor.dataType()) + " PRIMARY KEY");

        for (var attribute : root.attributes()) {
            // Skip ID attribute if it matches the IdDescriptor column to avoid duplicate column definition
            if (attribute instanceof BasicAttribute basic &&
                basic.location().column().equals(idDescriptor.columnName())) {
                continue;
            }
            columnDefinitions.addAll(generateColumnDefinitions(attribute));
        }

        return "CREATE TABLE " + qualifiedTableName + " (" + String.join(", ", columnDefinitions) + ")";
    }

    private List<String> generateColumnDefinitions(Attribute attribute) {
        return switch (attribute) {
            case BasicAttribute basic -> List.of(
                quoteIdentifier(basic.location().column()) + " " + mapDataTypeToSql(basic.dataType())
            );
            case CollectionAttribute collection -> switch (collection.elementType()) {
                case CollectionAttribute.BasicElement basicElement -> List.of(
                    quoteIdentifier(basicElement.location().column()) + " " +
                    mapDataTypeToSql(new DataType.ListType(basicElement.dataType()))
                );
                case CollectionAttribute.CompositeElement _ -> List.of();
            };
            case CompositeAttribute composite -> {
                var columns = new ArrayList<String>();
                for (var subAttribute : composite.attributes()) {
                    columns.addAll(generateColumnDefinitions(subAttribute));
                }
                yield columns;
            }
            case SingularReferenceAttribute ref -> switch (ref.mappingStrategy()) {
                case ReferenceAttribute.InverseRootTableColumn _, ReferenceAttribute.JoinTableMapping _ -> List.of();
                // FIXME
                case SameTableColumn(var columnName) -> List.of(
                    quoteIdentifier(columnName) + " " + mapIdTypeToSql(ref.targetRoot().idDescriptor().dataType())
                );
            };
            case PluralReferenceAttribute _ -> List.of();
        };
    }

    private String generateJoinTableDdl(
        String schemaName,
        Root ownerRoot,
        Root targetRoot,
        ReferenceAttribute.JoinTableMapping mapping
    ) {
        var joinTableName = quoteIdentifier(schemaName) + "." + quoteIdentifier(mapping.joinColumnLocation().table());
        var ownerColumnName = quoteIdentifier(mapping.joinColumnLocation().column());
        var targetColumnName = quoteIdentifier(mapping.inverseJoinColumnName());
        var ownerIdType = mapIdTypeToSql(ownerRoot.idDescriptor().dataType());
        var targetIdType = mapIdTypeToSql(targetRoot.idDescriptor().dataType());

        return "CREATE TABLE " + joinTableName + " (" +
               ownerColumnName + " " + ownerIdType + ", " +
               targetColumnName + " " + targetIdType + ", " +
               "PRIMARY KEY (" + ownerColumnName + ", " + targetColumnName + "))";
    }

    private String quoteIdentifier(String identifier) {
        // Escape any existing double quotes and wrap in quotes
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

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
            case DataType.CategorcialType _ -> "TEXT"; // Store as enum value string
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

    private String mapIdTypeToSql(DataType dataType) {
        return switch (dataType) {
            case DataType.NumericType numeric -> {
                if (numeric.precision() <= 4) {
                    yield "SMALLINT";
                } else if (numeric.precision() <= 9) {
                    yield "INTEGER";
                } else {
                    yield "BIGINT";
                }
            }
            case DataType.StringType _ -> "VARCHAR(255)";
            default -> throw new IllegalArgumentException("Unsupported ID type: " + dataType);
        };
    }
}
