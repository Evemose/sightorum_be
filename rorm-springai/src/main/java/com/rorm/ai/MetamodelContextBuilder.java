package com.rorm.ai;

import com.rorm.metamodel.*;
import com.rorm.metamodel.CollectionAttribute.BasicElement;
import com.rorm.metamodel.CollectionAttribute.CompositeElement;
import com.rorm.metamodel.DataType.*;
import lombok.RequiredArgsConstructor;

import java.util.stream.Collectors;

/**
 * Converts a ModelSpace into a human-readable schema description
 * that can be included in AI prompts for natural language query generation.
 */
@RequiredArgsConstructor
public class MetamodelContextBuilder {

    public String buildContext(ModelSpace modelSpace) {
        var sb = new StringBuilder();
        sb.append("# Database Schema\n\n");
        sb.append("The following entities are available for querying:\n\n");

        for (Root root : modelSpace.roots()) {
            sb.append(describeRoot(root));
            sb.append("\n");
        }

        sb.append("\n## Query Structure\n\n");
        sb.append("""
            To execute a query, construct a Query JSON object with the following structure:
            
            ```json
            {
              "from": { /* Root entity - reference one of the entities above */ },
              "selector": { /* What to SELECT */ },
              "where": { /* Optional: filter expression */ },
              "groupBy": { "expressions": [...] },
              "orderBy": [{ "expression": {...}, "ascending": true/false }],
              "limit": 100,
              "offset": 0
            }
            ```
            
            ### Selector Types (@type):
            - "root": Select all attributes from the entity
            - "single": Select one expression with optional alias
            - "multi": Select multiple expressions
            
            ### Expression Types (@type):
            - "path": Reference an attribute `{"@type":"path", "target": <attribute>}`
            - "literal": Constant value `{"@type":"literal", "value": 123}`
            - "binary": Comparison/logic `{"@type":"binary", "left":{...}, "operator":"EQUALS", "right":{...}}`
            - "unary": IS_NULL, NOT `{"@type":"unary", "operator":"IS_NULL", "operand":{...}}`
            - "aggregation": COUNT, SUM, AVG, MIN, MAX `{"@type":"aggregation", "functionName":"COUNT", "arguments":[...], "distinct":false}`
            
            ### Binary Operators:
            EQUALS, NOT_EQUALS, GREATER_THAN, LESS_THAN, GREATER_THAN_OR_EQUAL, LESS_THAN_OR_EQUAL,
            LIKE, NOT_LIKE, IN, NOT_IN, AND, OR, ADD, SUBTRACT, MULTIPLY, DIVIDE, MODULO
            
            ### Unary Operators:
            IS_NULL, IS_NOT_NULL, IS_TRUE, IS_FALSE, NOT, NEGATE
            """);

        return sb.toString();
    }

    private String describeRoot(Root root) {
        var sb = new StringBuilder();
        sb.append("## Entity: ").append(root.primaryTableName()).append("\n\n");

        if (root.idDescriptor() != null) {
            var idAttr = root.idDescriptor().idAttribute();
            sb.append("**Primary Key**: ").append(idAttr.name())
                .append(" (").append(describeDataType(idAttr.dataType())).append(")\n\n");
        }

        sb.append("### Attributes:\n");
        for (Attribute attr : root.attributes()) {
            sb.append(describeAttribute(attr, "  "));
        }

        return sb.toString();
    }

    private String describeAttribute(Attribute attr, String indent) {
        return switch (attr) {
            case BasicAttribute basic -> describeBasicAttribute(basic, indent);
            case CompositeAttribute composite -> describeCompositeAttribute(composite, indent);
            case SingularReferenceAttribute ref -> describeSingularReference(ref, indent);
            case PluralReferenceAttribute ref -> describePluralReference(ref, indent);
            case CollectionAttribute coll -> describeCollection(coll, indent);
        };
    }

    private String describeBasicAttribute(BasicAttribute attr, String indent) {
        return indent + "- **" + attr.name() + "**: " +
               describeDataType(attr.dataType()) +
               "\n";
    }

    private String describeCompositeAttribute(CompositeAttribute attr, String indent) {
        var sb = new StringBuilder();
        sb.append(indent).append("- **").append(attr.name()).append("** (embedded object):\n");
        for (Attribute nested : attr.attributes()) {
            sb.append(describeAttribute(nested, indent + "  "));
        }
        return sb.toString();
    }

    private String describeSingularReference(SingularReferenceAttribute attr, String indent) {
        return indent + "- **" + attr.name() + "**: Reference to " +
               attr.targetRoot().primaryTableName() + " (many-to-one or one-to-one)\n";
    }

    private String describePluralReference(PluralReferenceAttribute attr, String indent) {
        return indent + "- **" + attr.name() + "**: Collection of " +
               attr.targetRoot().primaryTableName() + " (one-to-many or many-to-many)\n";
    }

    private String describeCollection(CollectionAttribute attr, String indent) {
        var elementDesc = switch (attr.elementType()) {
            case BasicElement be -> describeDataType(be.dataType());
            case CompositeElement ce -> ce.attributes().stream()
                .map(a -> a.name() + ": " + (a instanceof BasicAttribute ba ? describeDataType(ba.dataType()) : "object"))
                .collect(Collectors.joining(", ", "{", "}"));
        };
        return indent + "- **" + attr.name() + "**: Collection of " + elementDesc + "\n";
    }

    private String describeDataType(DataType type) {
        return switch (type) {
            case NumericType n -> n.scale() > 0
                ? "decimal(" + n.precision() + "," + n.scale() + ")"
                : "integer";
            case StringType _ -> "string";
            case BooleanType _ -> "boolean";
            case DateType _ -> "date";
            case TimeType _ -> "time";
            case TimezoneType _ -> "timezone";
            case DateTimeType _ -> "datetime";
            case DayOfWeekType _ -> "day of week";
            case EnumType e -> "enum(" + String.join(", ", e.values()) + ")";
            case ListType l -> "list of " + describeDataType(l.elementType());
        };
    }
}
