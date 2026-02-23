package com.rorm.dataimport.hierarchical;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rorm.dataimport.attribute.NameUtils;
import com.rorm.dataimport.hierarchical.HierarchicalStructure.DetectedField;
import com.rorm.dataimport.hierarchical.HierarchicalStructure.DetectedRoot;
import com.rorm.dataimport.type.DataTypeDetector;
import com.rorm.dataimport.type.InMemoryCoercion;
import com.rorm.metamodel.DataType;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Base class for hierarchical data sources (JSON, YAML).
 * Provides common functionality for structure detection, column name extraction, and node flattening.
 */
@Slf4j
public abstract class AbstractHierarchicalDataSource implements HierarchicalDataSource {

    protected static final int STRUCTURE_SAMPLE_SIZE = 100;
    private static final String ID_FIELD_NAME = "id";

    protected final String rootName;
    protected final Path filePath;
    protected final DataTypeDetector typeDetector;

    @Nullable
    private List<String> cachedColumnNames;
    @Nullable
    private HierarchicalStructure cachedStructure;

    protected HierarchicalStructure analyzeStructure() {
        var sampleNodes = readSampleNodes();

        if (sampleNodes.isEmpty()) {
            return new HierarchicalStructure(Map.of(rootName, DetectedRoot.primary(rootName, Map.of())));
        }

        var roots = new LinkedHashMap<String, DetectedRoot>();
        var ctx = new FieldContext(rootName, "", roots);
        var fields = analyzeObjectFields(sampleNodes, ctx);
        roots.put(rootName, DetectedRoot.primary(rootName, fields));
        return new HierarchicalStructure(roots);
    }

    // 4B: Stream-based column flattening
    private List<String> detectColumnNames() {
        var structure = detectStructure();
        var primaryRoot = structure.roots().get(rootName);
        if (primaryRoot == null) {
            return List.of();
        }
        return flattenedColumns(primaryRoot.fields(), "").toList();
    }

    protected AbstractHierarchicalDataSource(Path filePath) {
        this.filePath = filePath;
        this.rootName = extractRootName(filePath);
        this.typeDetector = new DataTypeDetector();
    }

    private String extractRootName(Path filePath) {
        var fileName = filePath.getFileName().toString();
        var dotIndex = fileName.lastIndexOf('.');
        return dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName;
    }

    /**
     * Flattens a JsonNode into a Map with dot-notation keys.
     */
    public static Map<String, Object> flattenNode(@Nullable JsonNode node, List<String> columnNames, long rowId) {
        var result = new LinkedHashMap<String, @Nullable Object>();

        if (node != null && node.isObject()) {
            flattenObject((ObjectNode) node, "", result, rowId);
        }

        for (var col : columnNames) {
            result.putIfAbsent(col, null);
        }

        return result;
    }

    private static void flattenObject(ObjectNode node, String prefix, Map<String, @Nullable Object> result, long rowId) {
        for (var entry : node.properties()) {
            var fieldName = entry.getKey();
            var value = entry.getValue();
            var fullPath = prefix.isEmpty() ? fieldName : prefix + "." + fieldName;

            if (value.isNull()) {
                result.put(fullPath, null);
            } else if (value.isObject()) {
                flattenObject((ObjectNode) value, fullPath, result, rowId);
            } else if (value.isArray()) {
                flattenArray((ArrayNode) value, fullPath, result, rowId);
            } else {
                result.put(fullPath, extractValue(value));
            }
        }
    }

    private static void flattenArray(ArrayNode array, String path, Map<String, @Nullable Object> result, long rowId) {
        if (array.isEmpty()) {
            result.put(path, List.of());
            return;
        }

        var first = array.get(0);
        if (first.isObject()) {
            // For object arrays that become separate roots, store FK
            result.put(path + "_id", rowId);
        } else {
            // Scalar array - collect as list preserving types
            var values = new ArrayList<>();
            for (var elem : array) {
                if (!elem.isNull()) {
                    values.add(extractValue(elem));
                }
            }
            result.put(path, values);
        }
    }

    private static Object extractValue(JsonNode value) {
        if (value.isBoolean()) {
            return value.booleanValue();
        } else if (value.isIntegralNumber()) {
            return value.longValue();
        } else if (value.isFloatingPointNumber()) {
            return value.doubleValue();
        } else {
            return value.asText();
        }
    }

    @Override
    public String getRootName() {
        return rootName;
    }

    @Override
    public List<String> getColumnNames() {
        if (cachedColumnNames == null) {
            cachedColumnNames = detectColumnNames();
        }
        return cachedColumnNames;
    }

    @Override
    public HierarchicalStructure detectStructure() {
        if (cachedStructure == null) {
            cachedStructure = analyzeStructure();
        }
        return cachedStructure;
    }

    /**
     * Subclasses implement this to read sample nodes for structure analysis.
     */
    protected abstract List<JsonNode> readSampleNodes();

    private Stream<String> flattenedColumns(Map<String, DetectedField> fields, String prefix) {
        return fields.entrySet().stream().flatMap(entry -> {
            var fieldName = entry.getKey();
            var field = entry.getValue();
            var fullName = prefix.isEmpty() ? fieldName : prefix + "." + fieldName;

            return switch (field) {
                case DetectedField.Scalar _ -> Stream.of(fullName);
                case DetectedField.ScalarArray _ -> Stream.of(fullName);
                case DetectedField.Composite composite -> flattenedColumns(composite.fields(), fullName);
                case DetectedField.CompositeCollection coll -> flattenedColumns(coll.elementFields(), fullName);
                case DetectedField.SingularObjectRef _, DetectedField.PluralObjectRef _ -> Stream.of(fullName + "_id");
            };
        });
    }

    // 4A: Uses FieldContext to reduce parameter threading
    private Map<String, DetectedField> analyzeObjectFields(List<JsonNode> samples, FieldContext ctx) {
        var fieldNames = new LinkedHashSet<String>();
        for (var sample : samples) {
            if (sample.isObject()) {
                sample.fieldNames().forEachRemaining(fieldNames::add);
            }
        }

        var fields = new LinkedHashMap<String, DetectedField>();

        for (var fieldName : fieldNames) {
            var fieldSamples = samples.stream()
                .filter(JsonNode::isObject)
                .map(n -> n.get(fieldName))
                .filter(Objects::nonNull)
                .toList();

            if (fieldSamples.isEmpty()) {
                continue;
            }

            var field = analyzeField(fieldName, fieldSamples, ctx.withPath(fieldName));
            fields.put(fieldName, field);
        }

        return fields;
    }

    private DetectedField analyzeField(String fieldName, List<JsonNode> samples, FieldContext ctx) {
        var firstNonNull = samples.stream()
            .filter(n -> !n.isNull())
            .findFirst()
            .orElse(null);

        if (firstNonNull == null) {
            return new DetectedField.Scalar(fieldName, new DataType.StringType());
        }

        // 4C: Object analysis uses ObjectClassification
        if (firstNonNull.isObject()) {
            var classification = classifyObject(fieldName, samples, ctx);
            return classification.toSingular(fieldName);
        }

        if (firstNonNull.isArray()) {
            return analyzeArrayField(fieldName, samples, ctx);
        }

        // Scalar value
        var stringValues = samples.stream()
            .filter(n -> !n.isNull())
            .map(JsonNode::asText)
            .toList();
        var dataType = detectDataType(stringValues);
        return new DetectedField.Scalar(fieldName, dataType);
    }

    // 4C: Shared classification builds once, callers pick singular/plural
    private ObjectClassification classifyObject(String fieldName, List<JsonNode> samples, FieldContext ctx) {
        var objectSamples = samples.stream()
            .filter(JsonNode::isObject)
            .toList();

        if (hasIdField(objectSamples)) {
            var childRootName = deriveChildRootName(ctx.rootName(), fieldName);
            var childCtx = ctx.forChildRoot(childRootName);
            var childFields = analyzeObjectFields(objectSamples, childCtx);
            ctx.roots().put(childRootName, DetectedRoot.child(childRootName, childFields, ctx.rootName(), fieldName));
            return new ObjectClassification(childRootName, childFields);
        } else {
            var nestedFields = analyzeObjectFields(objectSamples, ctx);
            return new ObjectClassification(null, nestedFields);
        }
    }

    private DetectedField analyzeArrayField(String fieldName, List<JsonNode> samples, FieldContext ctx) {
        var allElements = samples.stream()
            .filter(JsonNode::isArray)
            .flatMap(arr -> StreamSupport.stream(arr.spliterator(), false))
            .toList();

        if (allElements.isEmpty()) {
            return new DetectedField.ScalarArray(fieldName, new DataType.StringType());
        }

        var firstElement = allElements.stream()
            .filter(n -> !n.isNull())
            .findFirst()
            .orElse(null);

        if (firstElement == null) {
            return new DetectedField.ScalarArray(fieldName, new DataType.StringType());
        }

        if (firstElement.isObject()) {
            // 4C: Reuse classification, pick plural variant
            var classification = classifyObject(fieldName, allElements, ctx);
            return classification.toPlural(fieldName);
        }

        // Array of scalars
        var stringValues = allElements.stream()
            .filter(n -> !n.isNull())
            .map(JsonNode::asText)
            .toList();
        var elementType = detectDataType(stringValues);
        return new DetectedField.ScalarArray(fieldName, elementType);
    }

    // 4A: FieldContext record reduces recursive parameter threading
    private record FieldContext(String rootName, String path, Map<String, DetectedRoot> roots) {
        FieldContext withPath(String fieldName) {
            var newPath = path.isEmpty() ? fieldName : path + "." + fieldName;
            return new FieldContext(rootName, newPath, roots);
        }

        FieldContext forChildRoot(String childRootName) {
            return new FieldContext(childRootName, "", roots);
        }
    }

    // 4C: ObjectClassification record replaces boolean isArray parameter
    private record ObjectClassification(@Nullable String childRootName, Map<String, DetectedField> fields) {
        DetectedField toSingular(String fieldName) {
            if (childRootName != null) {
                return new DetectedField.SingularObjectRef(fieldName, childRootName);
            }
            return new DetectedField.Composite(fieldName, fields);
        }

        DetectedField toPlural(String fieldName) {
            if (childRootName != null) {
                return new DetectedField.PluralObjectRef(fieldName, childRootName);
            }
            return new DetectedField.CompositeCollection(fieldName, fields);
        }
    }

    private boolean hasIdField(List<JsonNode> objectSamples) {
        return objectSamples.stream()
            .filter(JsonNode::isObject)
            .anyMatch(n -> n.has(ID_FIELD_NAME));
    }

    private String deriveChildRootName(String parentName, String fieldName) {
        var singular = NameUtils.singularize(fieldName);
        return parentName + "_" + singular;
    }

    private DataType detectDataType(List<String> values) {
        return typeDetector.detectType(values, InMemoryCoercion.Skip.INSTANCE);
    }

    protected void closeQuietly(@Nullable AutoCloseable resource) {
        if (resource != null) {
            try {
                resource.close();
            } catch (Exception e) {
                log.warn("Failed to close resource", e);
            }
        }
    }
}
