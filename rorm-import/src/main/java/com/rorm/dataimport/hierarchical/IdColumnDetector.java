package com.rorm.dataimport.hierarchical;

import com.rorm.dataimport.hierarchical.HierarchicalOverride.DataTypeOverride;
import com.rorm.dataimport.hierarchical.HierarchicalOverride.ForceSeparateRoot;
import com.rorm.dataimport.hierarchical.HierarchicalOverride.IdOverride;
import com.rorm.dataimport.hierarchical.HierarchicalStructure.DetectedField;
import com.rorm.dataimport.hierarchical.HierarchicalStructure.DetectedRoot;
import com.rorm.dataimport.pipeline.SchemaDetector.DetectedIdColumn;
import com.rorm.metamodel.DataType;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Optional;

/**
 * Handles ID column detection for hierarchical roots with various strategies.
 */
class IdColumnDetector {

    private static final String DEFAULT_ID_COLUMN = "id";
    private static final String SYNTHETIC_ID_SUFFIX = "_synthetic_id";

    private final OverrideFinder overrideFinder;

    IdColumnDetector(OverrideFinder overrideFinder) {
        this.overrideFinder = overrideFinder;
    }

    DetectedIdColumn detectIdColumn(DetectedRoot root, List<HierarchicalOverride> overrides) {
        return tryIdOverride(root, overrides)
            .or(() -> tryForceSeparateRootStrategy(root, overrides))
            .or(() -> tryExistingIdField(root, overrides))
            .or(() -> tryChildRootSyntheticId(root))
            .orElseGet(() -> createDefaultSyntheticId(root));
    }

    private Optional<DetectedIdColumn> tryIdOverride(DetectedRoot root, List<HierarchicalOverride> overrides) {
        return overrideFinder.findIdOverride(root.name(), overrides)
            .map(override -> processIdOverride(root, override));
    }

    private Optional<DetectedIdColumn> tryForceSeparateRootStrategy(
        DetectedRoot root,
        List<HierarchicalOverride> overrides
    ) {
        return overrideFinder.findForceSeparateRootForChildRoot(root, overrides)
            .map(override -> processIdStrategy(root, override));
    }

    private Optional<DetectedIdColumn> tryExistingIdField(DetectedRoot root, List<HierarchicalOverride> overrides) {
        var idField = root.fields().get(DEFAULT_ID_COLUMN);
        if (!(idField instanceof DetectedField.Scalar scalar)) {
            return Optional.empty();
        }

        var dataType = overrideFinder.findOverride(overrides, DEFAULT_ID_COLUMN, DataTypeOverride.class)
            .map(DataTypeOverride::dataType)
            .orElse(scalar.dataType());

        return Optional.of(new DetectedIdColumn(DEFAULT_ID_COLUMN, DEFAULT_ID_COLUMN, dataType));
    }

    private Optional<DetectedIdColumn> tryChildRootSyntheticId(DetectedRoot root) {
        if (root.isPrimary()) {
            return Optional.empty();
        }

        var syntheticIdName = root.name() + SYNTHETIC_ID_SUFFIX;
        return Optional.of(new DetectedIdColumn(syntheticIdName, syntheticIdName, new DataType.NumericType(19, 0)));
    }

    private DetectedIdColumn createDefaultSyntheticId(DetectedRoot root) {
        var syntheticIdName = root.name() + SYNTHETIC_ID_SUFFIX;
        return new DetectedIdColumn(syntheticIdName, syntheticIdName, new DataType.NumericType(19, 0));
    }

    private DetectedIdColumn processIdOverride(DetectedRoot root, IdOverride override) {
        var fieldName = override.fieldName() != null ? override.fieldName() : DEFAULT_ID_COLUMN;

        validateIdOverrideField(root, override, fieldName);

        var dataType = determineIdDataType(root, override.dataType(), fieldName);
        return new DetectedIdColumn(fieldName, fieldName, dataType);
    }

    private DetectedIdColumn processIdStrategy(DetectedRoot root, ForceSeparateRoot override) {
        var idStrategy = override.idStrategy();
        return switch (idStrategy) {
            case HierarchicalOverride.IdStrategy.UseField useField -> {
                var dataType = useField.dataType() != null
                    ? useField.dataType()
                    : new DataType.NumericType(19, 0);
                yield new DetectedIdColumn(useField.fieldName(), useField.fieldName(), dataType);
            }
            case HierarchicalOverride.IdStrategy.AutoGenerate _ -> {
                var syntheticIdName = root.name() + SYNTHETIC_ID_SUFFIX;
                yield new DetectedIdColumn(syntheticIdName, syntheticIdName, new DataType.NumericType(19, 0));
            }
        };
    }

    private void validateIdOverrideField(DetectedRoot root, IdOverride override, String fieldName) {
        if (override.fieldName() == null) {
            return;
        }

        var field = root.fields().get(fieldName);
        if (field == null) {
            throw new IllegalArgumentException(
                "IdOverride for root '" + root.name() + "' specifies field '" + fieldName +
                "' which does not exist. Available fields: " + root.fields().keySet()
            );
        }
        if (!(field instanceof DetectedField.Scalar)) {
            throw new IllegalArgumentException(
                "IdOverride for root '" + root.name() + "' specifies field '" + fieldName +
                "' which is not a scalar field (type: " + field.getClass().getSimpleName() + ")"
            );
        }
    }

    private DataType determineIdDataType(DetectedRoot root, @Nullable DataType overrideDataType, String fieldName) {
        if (overrideDataType != null) {
            return overrideDataType;
        }

        var field = root.fields().get(fieldName);
        if (field instanceof DetectedField.Scalar scalar) {
            return scalar.dataType();
        }

        return new DataType.NumericType(19, 0);
    }
}
