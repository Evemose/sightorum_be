package com.rorm.dataimport.hierarchical;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rorm.dataimport.attribute.NameUtils;
import com.rorm.dataimport.hierarchical.HierarchicalOverride.ForceComposite;
import com.rorm.dataimport.hierarchical.HierarchicalOverride.ForceSeparateRoot;
import com.rorm.dataimport.hierarchical.HierarchicalStructure.DetectedField;
import com.rorm.dataimport.hierarchical.HierarchicalStructure.DetectedRoot;
import com.rorm.dataimport.type.DataTypeDetector;
import com.rorm.dataimport.type.NullCoalescingStrategy;
import com.rorm.metamodel.DataType;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.*;
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
    protected final List<HierarchicalOverride> overrides;

    @Nullable
    private List<String> cachedColumnNames;
    @Nullable
    private HierarchicalStructure cachedStructure;

    protected AbstractHierarchicalDataSource(Path filePath, List<HierarchicalOverride> overrides) {
        this.filePath = filePath;
        this.rootName = extractRootName(filePath);
        this.typeDetector = new DataTypeDetector();
        this.overrides = overrides;
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

    protected HierarchicalStructure analyzeStructure() {
        var sampleNodes = readSampleNodes();

        if (sampleNodes.isEmpty()) {
            return new HierarchicalStructure(Map.of(rootName, DetectedRoot.primary(rootName, Map.of())));
        }

        var roots = new LinkedHashMap<String, DetectedRoot>();
        var fields = analyzeObjectFields(sampleNodes, rootName, "", roots);
        roots.put(rootName, DetectedRoot.primary(rootName, fields));
        return new HierarchicalStructure(roots);
    }

    private List<String> detectColumnNames() {
        var structure = detectStructure();
        var primaryRoot = structure.roots().get(rootName);
        if (primaryRoot == null) {
            return List.of();
        }
        var columns = new ArrayList<String>();
        collectFlattenedColumns(primaryRoot.fields(), "", columns);
        return List.copyOf(columns);
    }

    private void collectFlattenedColumns(Map<String, DetectedField> fields, String prefix, List<String> columns) {
        for (var entry : fields.entrySet()) {
            var fieldName = entry.getKey();
            var field = entry.getValue();
            var fullName = prefix.isEmpty() ? fieldName : prefix + "." + fieldName;

            switch (field) {
                case DetectedField.Scalar _ -> columns.add(fullName);
                case DetectedField.ScalarArray _ -> columns.add(fullName);
                case DetectedField.Composite composite ->
                    collectFlattenedColumns(composite.fields(), fullName, columns);
                case DetectedField.CompositeCollection coll ->
                    collectFlattenedColumns(coll.elementFields(), fullName, columns);
                case DetectedField.SingularObjectRef _, DetectedField.PluralObjectRef _ ->
                    columns.add(fullName + "_id");
            }
        }
    }

    private Map<String, DetectedField> analyzeObjectFields(
        List<JsonNode> samples,
        String currentRootName,
        String currentPath,
        Map<String, DetectedRoot> roots
    ) {
        var fieldNames = new LinkedHashSet<String>();
        for (var sample : samples) {
            if (sample.isObject()) {
                sample.fieldNames().forEachRemaining(fieldNames::add);
            }
        }

        var fields = new LinkedHashMap<String, DetectedField>();

        for (var fieldName : fieldNames) {
            var fieldPath = currentPath.isEmpty() ? fieldName : currentPath + "." + fieldName;
            var fieldSamples = samples.stream()
                .filter(JsonNode::isObject)
                .map(n -> n.get(fieldName))
                .filter(Objects::nonNull)
                .toList();

            if (fieldSamples.isEmpty()) {
                continue;
            }

            var field = analyzeField(fieldName, fieldPath, fieldSamples, currentRootName, roots);
            fields.put(fieldName, field);
        }

        return fields;
    }

    private DetectedField analyzeField(
        String fieldName,
        String fieldPath,
        List<JsonNode> samples,
        String currentRootName,
        Map<String, DetectedRoot> roots
    ) {
        var firstNonNull = samples.stream()
            .filter(n -> !n.isNull())
            .findFirst()
            .orElse(null);

        if (firstNonNull == null) {
            return new DetectedField.Scalar(fieldName, new DataType.StringType());
        }

        if (firstNonNull.isObject()) {
            return analyzeObjectField(fieldName, fieldPath, samples, currentRootName, roots, false);
        }

        if (firstNonNull.isArray()) {
            return analyzeArrayField(fieldName, fieldPath, samples, currentRootName, roots);
        }

        // Scalar value
        var stringValues = samples.stream()
            .filter(n -> !n.isNull())
            .map(JsonNode::asText)
            .toList();
        var dataType = detectDataType(fieldPath, stringValues);
        return new DetectedField.Scalar(fieldName, dataType);
    }

    private DetectedField analyzeObjectField(
        String fieldName,
        String fieldPath,
        List<JsonNode> samples,
        String currentRootName,
        Map<String, DetectedRoot> roots,
        boolean isArray
    ) {
        var objectSamples = samples.stream()
            .filter(JsonNode::isObject)
            .toList();

        // Check for explicit overrides
        var forceComposite = findOverride(fieldPath, ForceComposite.class);
        var forceSeparateRoot = findOverride(fieldPath, ForceSeparateRoot.class);

        boolean treatAsSeparateRoot;
        if (forceComposite.isPresent()) {
            treatAsSeparateRoot = false;
        } else if (forceSeparateRoot.isPresent()) {
            treatAsSeparateRoot = true;
        } else {
            // Default: check if nested objects have an "id" field
            treatAsSeparateRoot = hasIdField(objectSamples);
        }

        if (treatAsSeparateRoot) {
            var childRootName = deriveChildRootName(currentRootName, fieldName);
            var childFields = analyzeObjectFields(objectSamples, childRootName, "", roots);
            roots.put(childRootName, DetectedRoot.child(childRootName, childFields, currentRootName, fieldName));

            return isArray
                ? new DetectedField.PluralObjectRef(fieldName, childRootName)
                : new DetectedField.SingularObjectRef(fieldName, childRootName);
        } else {
            var nestedFields = analyzeObjectFields(objectSamples, currentRootName, fieldPath, roots);

            return isArray
                ? new DetectedField.CompositeCollection(fieldName, nestedFields)
                : new DetectedField.Composite(fieldName, nestedFields);
        }
    }

    private DetectedField analyzeArrayField(
        String fieldName,
        String fieldPath,
        List<JsonNode> samples,
        String currentRootName,
        Map<String, DetectedRoot> roots
    ) {
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
            // Delegate to object analysis with isArray=true
            return analyzeObjectField(fieldName, fieldPath, allElements, currentRootName, roots, true);
        }

        // Array of scalars
        var stringValues = allElements.stream()
            .filter(n -> !n.isNull())
            .map(JsonNode::asText)
            .toList();
        var elementType = detectDataType(fieldPath, stringValues);
        return new DetectedField.ScalarArray(fieldName, elementType);
    }

    private boolean hasIdField(List<JsonNode> objectSamples) {
        return objectSamples.stream()
            .filter(JsonNode::isObject)
            .anyMatch(n -> n.has(ID_FIELD_NAME));
    }

    private <T extends HierarchicalOverride> Optional<T> findOverride(String fieldPath, Class<T> type) {
        return overrides.stream()
            .filter(type::isInstance)
            .map(type::cast)
            .filter(o -> o.fieldPath().equals(fieldPath))
            .findFirst();
    }

    private String deriveChildRootName(String parentName, String fieldName) {
        var singular = NameUtils.singularize(fieldName);
        return parentName + "_" + singular;
    }

    private DataType detectDataType(String fieldPath, List<String> values) {
        return overrides.stream()
            .filter(o -> o instanceof HierarchicalOverride.DataTypeOverride)
            .map(o -> (HierarchicalOverride.DataTypeOverride) o)
            .filter(o -> o.fieldPath().equals(fieldPath))
            .findFirst()
            .map(HierarchicalOverride.DataTypeOverride::dataType)
            .orElseGet(() -> typeDetector.detectType(values, NullCoalescingStrategy.SkipNulls.INSTANCE));
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
