package com.rorm.dataimport.override;

import com.rorm.metamodel.DataType;
import org.jspecify.annotations.Nullable;

import java.util.List;

public sealed interface SchemaOverride permits
    SchemaOverride.BasicAttributeOverride,
    SchemaOverride.CompositeAttributeOverride,
    SchemaOverride.SingularReferenceOverride,
    SchemaOverride.PluralReferenceOverride,
    SchemaOverride.CollectionAttributeOverride,
    SchemaOverride.OneToOneRootOverride,
    SchemaOverride.IdAttributeOverride {

    String attributeName();

    record BasicAttributeOverride(
        String attributeName,
        @Nullable DataType dataType
    ) implements SchemaOverride {
    }

    record CompositeAttributeOverride(
        String attributeName,
        List<String> subAttributeColumns,
        List<SchemaOverride> nestedOverrides
    ) implements SchemaOverride {
    }

    record SingularReferenceOverride(
        String attributeName,
        String targetRootName
    ) implements SchemaOverride {
    }

    record PluralReferenceOverride(
        String attributeName,
        String targetRootName
    ) implements SchemaOverride {
    }

    record CollectionAttributeOverride(
        String attributeName,
        @Nullable DataType elementType,
        @Nullable String separator
    ) implements SchemaOverride {
    }

    record OneToOneRootOverride(
        String attributeName,
        String targetRootName,
        List<String> subAttributeColumns,
        List<SchemaOverride> nestedOverrides,
        @Nullable String idColumn
    ) implements SchemaOverride {
    }

    record IdAttributeOverride(
        @Nullable String attributeName,
        String columnName,
        @Nullable DataType dataType
    ) implements SchemaOverride {
    }
}
