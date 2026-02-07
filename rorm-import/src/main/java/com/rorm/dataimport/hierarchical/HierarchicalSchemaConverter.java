package com.rorm.dataimport.hierarchical;

import com.rorm.dataimport.attribute.DetectedAttribute;
import com.rorm.dataimport.attribute.NameUtils;
import com.rorm.dataimport.hierarchical.HierarchicalOverride.ForceBasic;
import com.rorm.dataimport.hierarchical.HierarchicalOverride.ForceComposite;
import com.rorm.dataimport.hierarchical.HierarchicalOverride.ForceReference;
import com.rorm.dataimport.hierarchical.HierarchicalOverride.ForceSeparateRoot;
import com.rorm.dataimport.hierarchical.HierarchicalStructure.DetectedField;
import com.rorm.dataimport.hierarchical.HierarchicalStructure.DetectedRoot;
import com.rorm.dataimport.pipeline.DetectedSchema;
import com.rorm.dataimport.pipeline.SchemaDetector;
import com.rorm.dataimport.pipeline.SourceMapping;
import com.rorm.metamodel.DataType;

import java.util.*;

/**
 * Converts organically detected hierarchical structure to the standard DetectedSchema format.
 * <p>
 * Applies hierarchical overrides during conversion:
 * <ul>
 *   <li>DataTypeOverride - Override detected data type for scalar fields</li>
 *   <li>ForceComposite - Force nested objects to be embedded composites</li>
 *   <li>ForceSeparateRoot - Force nested objects to become separate roots</li>
 *   <li>ForceReference - Force scalar fields to be references</li>
 *   <li>ForceBasic - Force objects/references to be basic attributes</li>
 *   <li>IdOverride - Override ID column configuration</li>
 * </ul>
 */
public class HierarchicalSchemaConverter {

    private static final ScopedValue<TransformationContext> CONTEXT = ScopedValue.newInstance();

    private final OverrideFinder overrideFinder;
    private final OverrideHandler overrideHandler;
    private final IdColumnDetector idColumnDetector;
    private final FieldConverter fieldConverter;

    public HierarchicalSchemaConverter() {
        this.overrideFinder = new OverrideFinder();
        this.overrideHandler = new OverrideHandler();
        var referenceDetector = new ExternalReferenceDetector();
        this.fieldConverter = new FieldConverter(overrideFinder, referenceDetector);
        this.idColumnDetector = new IdColumnDetector(overrideFinder);
    }

    private static TransformationContext context() {
        return CONTEXT.get();
    }

    /**
     * Converts a HierarchicalStructure to DetectedSchema for use with the import pipeline.
     */
    public DetectedSchema convert(HierarchicalStructure structure, String dataSourceName) {
        return convert(structure, dataSourceName, Set.of(), List.of());
    }

    /**
     * Converts a HierarchicalStructure to DetectedSchema for use with the import pipeline.
     */
    public DetectedSchema convert(HierarchicalStructure structure, String dataSourceName, Set<String> allRootNames) {
        return convert(structure, dataSourceName, allRootNames, List.of());
    }

    /**
     * Converts a HierarchicalStructure to DetectedSchema for use with the import pipeline,
     * applying the provided overrides.
     */
    public DetectedSchema convert(
        HierarchicalStructure structure,
        String dataSourceName,
        Set<String> allRootNames,
        List<HierarchicalOverride> overrides
    ) {
        var transformedStructure = transformStructure(structure, dataSourceName, overrides);
        var detectedRoots = new LinkedHashMap<String, SchemaDetector.DetectedRoot>();

        for (var entry : transformedStructure.roots().entrySet()) {
            var rootName = entry.getKey();
            var hierarchicalRoot = entry.getValue();
            var detectedRoot = convertRoot(hierarchicalRoot, dataSourceName, allRootNames, overrides);
            detectedRoots.put(rootName, detectedRoot);
        }

        return new DetectedSchema(detectedRoots);
    }

    /**
     * Transforms the structure based on ForceComposite and ForceSeparateRoot overrides.
     */
    private HierarchicalStructure transformStructure(
        HierarchicalStructure structure,
        String dataSourceName,
        List<HierarchicalOverride> overrides
    ) {
        var newRoots = new LinkedHashMap<String, DetectedRoot>();
        var ctx = new TransformationContext(dataSourceName, newRoots, structure.roots(), overrides);

        return ScopedValue.where(CONTEXT, ctx).call(() -> {
            for (var entry : structure.roots().entrySet()) {
                var rootName = entry.getKey();
                var root = entry.getValue();
                var transformedFields = transformFields(root.fields(), "", rootName);
                newRoots.put(rootName, new DetectedRoot(rootName, transformedFields, root.parentRootName(), root.parentFieldName()));
            }
            return new HierarchicalStructure(newRoots);
        });
    }

    private Map<String, DetectedField> transformFields(
        Map<String, DetectedField> fields,
        String pathPrefix,
        String currentRootName
    ) {
        var result = new LinkedHashMap<String, DetectedField>();

        for (var entry : fields.entrySet()) {
            var fieldName = entry.getKey();
            var field = entry.getValue();
            var fieldPath = pathPrefix.isEmpty() ? fieldName : pathPrefix + "." + fieldName;
            var transformedField = transformField(field, fieldPath, currentRootName);
            result.put(fieldName, transformedField);
        }

        return result;
    }

    private DetectedField transformField(
        DetectedField field,
        String fieldPath,
        String currentRootName
    ) {
        var forceComposite = overrideFinder.findOverride(context().overrides(), fieldPath, ForceComposite.class);
        var forceSeparateRoot = overrideFinder.findOverride(context().overrides(), fieldPath, ForceSeparateRoot.class);

        return switch (field) {
            case DetectedField.Scalar scalar -> scalar;
            case DetectedField.ScalarArray array -> array;
            case DetectedField.Composite composite ->
                transformComposite(composite, fieldPath, currentRootName, forceSeparateRoot);
            case DetectedField.CompositeCollection coll ->
                transformCompositeCollection(coll, fieldPath, currentRootName, forceSeparateRoot);
            case DetectedField.SingularObjectRef ref -> transformSingularObjectRef(ref, forceComposite);
            case DetectedField.PluralObjectRef ref -> transformPluralObjectRef(ref, currentRootName, forceComposite);
        };
    }

    private DetectedField transformComposite(
        DetectedField.Composite composite,
        String fieldPath,
        String currentRootName,
        Optional<ForceSeparateRoot> forceSeparateRoot
    ) {
        if (forceSeparateRoot.isPresent()) {
            var childRootName = deriveChildRootName(currentRootName, composite.name());
            var childFields = transformFields(composite.fields(), "", childRootName);
            context().newRoots().put(childRootName, DetectedRoot.child(childRootName, childFields, currentRootName, composite.name()));
            return new DetectedField.SingularObjectRef(composite.name(), childRootName);
        }

        var nestedFields = transformFields(composite.fields(), fieldPath, currentRootName);
        return new DetectedField.Composite(composite.name(), nestedFields);
    }

    private DetectedField transformCompositeCollection(
        DetectedField.CompositeCollection coll,
        String fieldPath,
        String currentRootName,
        Optional<ForceSeparateRoot> forceSeparateRoot
    ) {
        if (forceSeparateRoot.isPresent()) {
            var childRootName = deriveChildRootName(currentRootName, coll.name());
            var childFields = transformFields(coll.elementFields(), "", childRootName);
            context().newRoots().put(childRootName, DetectedRoot.child(childRootName, childFields, currentRootName, coll.name()));
            return new DetectedField.PluralObjectRef(coll.name(), childRootName);
        }

        var nestedFields = transformFields(coll.elementFields(), fieldPath, currentRootName);
        return new DetectedField.CompositeCollection(coll.name(), nestedFields);
    }

    private DetectedField transformSingularObjectRef(
        DetectedField.SingularObjectRef ref,
        Optional<ForceComposite> forceComposite
    ) {
        if (forceComposite.isEmpty()) {
            return ref;
        }

        // Look up the child root and inline its fields as a composite
        var childRoot = context().originalRoots().get(ref.targetRootName());
        if (childRoot == null) {
            return ref;
        }

        var inlinedFields = transformFields(childRoot.fields(), "", ref.targetRootName());
        return new DetectedField.Composite(ref.name(), inlinedFields);
    }

    private DetectedField transformPluralObjectRef(
        DetectedField.PluralObjectRef ref,
        String currentRootName,
        Optional<ForceComposite> forceComposite
    ) {
        if (forceComposite.isEmpty()) {
            return ref;
        }

        // Look up the child root and inline its fields as a composite collection
        var childRoot = context().originalRoots().get(ref.targetRootName());
        if (childRoot == null) {
            return ref;
        }

        var inlinedFields = transformFields(childRoot.fields(), "", ref.targetRootName());
        return new DetectedField.CompositeCollection(ref.name(), inlinedFields);
    }

    private SchemaDetector.DetectedRoot convertRoot(
        DetectedRoot hierarchicalRoot,
        String dataSourceName,
        Set<String> allRootNames,
        List<HierarchicalOverride> overrides
    ) {
        var attributes = new LinkedHashMap<String, DetectedAttribute>();
        convertFields(hierarchicalRoot.fields(), "", attributes, dataSourceName, allRootNames, hierarchicalRoot.name(), overrides);

        var idColumn = idColumnDetector.detectIdColumn(hierarchicalRoot, overrides);

        if (!hierarchicalRoot.isPrimary()) {
            addParentReference(attributes, hierarchicalRoot, dataSourceName);
        }

        return new SchemaDetector.DetectedRoot(hierarchicalRoot.name(), dataSourceName, attributes, idColumn);
    }

    private void addParentReference(
        Map<String, DetectedAttribute> attributes,
        DetectedRoot hierarchicalRoot,
        String dataSourceName
    ) {
        var parentRootName = Objects.requireNonNull(hierarchicalRoot.parentRootName());
        var parentRefName = parentRootName + "_id";
        var parentRefSource = new SourceMapping(dataSourceName, parentRefName);
        attributes.put(parentRefName, new DetectedAttribute.SingularReference(
            parentRefName,
            parentRefSource,
            parentRootName,
            new DataType.NumericType(19, 0)
        ));
    }

    private void convertFields(
        Map<String, DetectedField> fields,
        String prefix,
        Map<String, DetectedAttribute> attributes,
        String dataSourceName,
        Set<String> allRootNames,
        String currentRootName,
        List<HierarchicalOverride> overrides
    ) {
        for (var entry : fields.entrySet()) {
            var fieldName = entry.getKey();
            var field = entry.getValue();
            var fullPath = prefix.isEmpty() ? fieldName : prefix + "." + fieldName;
            var attribute = convertField(field, fullPath, dataSourceName, allRootNames, currentRootName, overrides);
            attributes.put(attribute.name(), attribute);
        }
    }

    private DetectedAttribute convertField(
        DetectedField field,
        String sourcePath,
        String dataSourceName,
        Set<String> allRootNames,
        String currentRootName,
        List<HierarchicalOverride> overrides
    ) {
        var source = new SourceMapping(dataSourceName, sourcePath);

        // Try ForceReference override
        var forceRefOpt = overrideFinder.findOverride(overrides, sourcePath, ForceReference.class);
        if (forceRefOpt.isPresent()) {
            var result = overrideHandler.applyForceReference(field, forceRefOpt.get(), source, allRootNames, sourcePath);
            if (result.isPresent()) {
                return result.get();
            }
        }

        // Try ForceBasic override
        var forceBasicOpt = overrideFinder.findOverride(overrides, sourcePath, ForceBasic.class);
        if (forceBasicOpt.isPresent()) {
            var result = overrideHandler.applyForceBasic(field, forceBasicOpt.get(), source);
            if (result.isPresent()) {
                return result.get();
            }
        }

        // Default conversion
        return convertFieldDefault(field, sourcePath, source, dataSourceName, allRootNames, currentRootName, overrides);
    }

    private DetectedAttribute convertFieldDefault(
        DetectedField field,
        String sourcePath,
        SourceMapping source,
        String dataSourceName,
        Set<String> allRootNames,
        String currentRootName,
        List<HierarchicalOverride> overrides
    ) {
        return switch (field) {
            case DetectedField.Scalar scalar -> fieldConverter.convertScalar(
                scalar, sourcePath, source, allRootNames, currentRootName, overrides
            );
            case DetectedField.ScalarArray array -> fieldConverter.convertScalarArray(
                array, sourcePath, source, overrides
            );
            case DetectedField.Composite composite -> fieldConverter.convertComposite(
                composite, sourcePath, dataSourceName, allRootNames, currentRootName, overrides, this::convertFields
            );
            case DetectedField.CompositeCollection coll -> fieldConverter.convertCompositeCollection(
                coll, sourcePath, dataSourceName, allRootNames, currentRootName, overrides, this::convertFields
            );
            case DetectedField.SingularObjectRef ref -> fieldConverter.convertSingularObjectRef(ref, source);
            case DetectedField.PluralObjectRef ref -> fieldConverter.convertPluralObjectRef(ref, source);
        };
    }

    private String deriveChildRootName(String parentName, String fieldName) {
        var singular = NameUtils.singularize(fieldName);
        return parentName + "_" + singular;
    }
}
