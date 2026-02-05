package com.rorm.ai;

import com.rorm.metamodel.*;
import com.rorm.metamodel.CollectionAttribute.BasicElement;
import com.rorm.metamodel.CollectionAttribute.CompositeElement;
import com.rorm.metamodel.DataType.*;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Converts a ModelSpace into a human-readable schema description
 * that can be included in AI prompts for natural language query generation.
 *
 * <p>Following best practices for LLM schema formatting:
 * <ul>
 *   <li>Uses XML tags for clear structural boundaries</li>
 *   <li>Formats for readability, not raw JSON dumps</li>
 *   <li>Includes metadata that helps agents make better decisions</li>
 *   <li>Clearly documents relationships between entities</li>
 * </ul>
 *
 * <p>The output is designed to be included in the system prompt and cached
 * across multiple agent interactions for the same ModelSpace.
 */
@RequiredArgsConstructor
public class MetamodelContextBuilder {

    /**
     * Optional metadata provider for enriching schema with runtime statistics.
     * When provided, includes row counts, common values, and index information.
     */
    @Nullable
    private final SchemaMetadataProvider metadataProvider;

    public MetamodelContextBuilder() {
        this(null);
    }

    /**
     * Build the complete schema context for inclusion in AI prompts.
     *
     * @param modelSpace The model space containing all available entities
     * @return Formatted schema description with XML structure
     */
    public String buildContext(ModelSpace modelSpace) {
        var sb = new StringBuilder();

        // Entities section
        sb.append("## Available Entities\n\n");
        sb.append("The following entities are available for querying:\n\n");

        for (Root root : modelSpace.roots()) {
            sb.append(describeRoot(root, modelSpace));
            sb.append("\n");
        }

        // Relationships summary
        var relationships = extractRelationships(modelSpace);
        if (!relationships.isEmpty()) {
            sb.append("## Relationships\n\n");
            sb.append("```\n");
            for (String rel : relationships) {
                sb.append(rel).append("\n");
            }
            sb.append("```\n");
        }

        return sb.toString();
    }

    private String describeRoot(Root root, ModelSpace modelSpace) {
        var sb = new StringBuilder();
        sb.append("### Entity: `").append(root.primaryTableName()).append("`\n\n");

        // Primary key information
        if (root.idDescriptor() != null) {
            var idAttr = root.idDescriptor().idAttribute();
            sb.append("**Primary Key**: `").append(idAttr.name())
                .append("` (").append(describeDataType(idAttr.dataType())).append(")\n\n");
        }

        // Optional metadata (row count, etc.)
        if (metadataProvider != null) {
            var metadata = metadataProvider.getMetadata(root.primaryTableName());
            if (metadata != null) {
                sb.append("*Approximate row count: ~").append(formatNumber(metadata.approximateRowCount())).append("*\n\n");
            }
        }

        // Attributes table format for better readability
        sb.append("| Attribute | Type | Description |\n");
        sb.append("|-----------|------|-------------|\n");

        for (Attribute attr : root.attributes()) {
            sb.append(describeAttributeTableRow(attr, root, modelSpace));
        }

        return sb.toString();
    }

    /**
     * Extract all relationships between entities for a summary view.
     */
    private List<String> extractRelationships(ModelSpace modelSpace) {
        return modelSpace.roots().stream()
            .flatMap(root -> root.attributes().stream()
                .filter(ReferenceAttribute.class::isInstance)
                .map(attr -> formatRelationship(root, (ReferenceAttribute) attr)))
            .toList();
    }

    private String formatNumber(long number) {
        if (number >= 1_000_000) {
            return String.format("%.1fM", number / 1_000_000.0);
        } else if (number >= 1_000) {
            return String.format("%.1fK", number / 1_000.0);
        }
        return String.valueOf(number);
    }

    /**
     * Describe an attribute as a markdown table row for compact, readable display.
     */
    private String describeAttributeTableRow(Attribute attr, Root root, ModelSpace modelSpace) {
        return switch (attr) {
            case BasicAttribute basic ->
                "| `%s` | %s | - |%n".formatted(basic.name(), describeDataType(basic.dataType()));

            case CompositeAttribute composite -> {
                var nestedDesc = composite.attributes().stream()
                    .filter(BasicAttribute.class::isInstance)
                    .map(Attribute::name)
                    .collect(Collectors.joining(", "));
                yield "| `%s` | embedded | Contains: %s |%n".formatted(composite.name(), nestedDesc);
            }

            case SingularReferenceAttribute ref ->
                "| `%s` | → `%s` | Reference (many-to-one) |%n".formatted(ref.name(), ref.targetRoot().primaryTableName());

            case PluralReferenceAttribute ref ->
                "| `%s` | → `%s`[] | Collection (one-to-many) |%n".formatted(ref.name(), ref.targetRoot().primaryTableName());

            case CollectionAttribute coll -> {
                var elementDesc = switch (coll.elementType()) {
                    case BasicElement be -> describeDataType(be.dataType());
                    case CompositeElement ce -> "{" + ce.attributes().stream()
                        .filter(BasicAttribute.class::isInstance)
                        .map(Attribute::name)
                        .collect(Collectors.joining(", ")) + "}";
                };
                yield "| `%s` | %s[] | Element collection |%n".formatted(coll.name(), elementDesc);
            }
        };
    }

    private String formatRelationship(Root source, ReferenceAttribute ref) {
        var cardinality = switch (ref) {
            case SingularReferenceAttribute _ -> "many-to-one";
            case PluralReferenceAttribute _ -> "one-to-many";
        };
        return "%s.%s → %s (%s)".formatted(
            source.primaryTableName(),
            ref.name(),
            ref.targetRoot().primaryTableName(),
            cardinality
        );
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
