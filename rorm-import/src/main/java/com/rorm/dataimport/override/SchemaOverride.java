package com.rorm.dataimport.override;

import org.jspecify.annotations.Nullable;

import java.util.List;

public sealed interface SchemaOverride permits
    SchemaOverride.BasicAttributeOverride,
    SchemaOverride.CompositeAttributeOverride,
    SchemaOverride.SingularReferenceOverride,
    SchemaOverride.PluralReferenceOverride,
    SchemaOverride.CollectionAttributeOverride,
    SchemaOverride.OneToOneRootOverride {

    String attributeName();

    record BasicAttributeOverride(
        String attributeName,
        String descriptor
    ) implements SchemaOverride {
    }

    record CompositeAttributeOverride(
        String attributeName,
        List<String> subAttributeColumns,
        @Nullable List<SchemaOverride> nestedOverrides
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
        @Nullable String separator
    ) implements SchemaOverride {
    }

    record OneToOneRootOverride(
        String attributeName,
        String targetRootName,
        List<String> subAttributeColumns,
        @Nullable List<SchemaOverride> nestedOverrides
    ) implements SchemaOverride {
    }
}
