package com.rorm.dataimport.hierarchical;

import com.rorm.dataimport.hierarchical.HierarchicalOverride.ForceSeparateRoot;
import com.rorm.dataimport.hierarchical.HierarchicalOverride.IdOverride;
import com.rorm.dataimport.hierarchical.HierarchicalStructure.DetectedRoot;

import java.util.List;
import java.util.Optional;

/**
 * Utility class for finding hierarchical overrides by various criteria.
 */
class OverrideFinder {

    <T extends HierarchicalOverride> Optional<T> findOverride(
        List<HierarchicalOverride> overrides,
        String fieldPath,
        Class<T> type
    ) {
        return overrides.stream()
            .filter(type::isInstance)
            .map(type::cast)
            .filter(o -> o.fieldPath().equals(fieldPath))
            .findFirst();
    }

    Optional<IdOverride> findIdOverride(String rootName, List<HierarchicalOverride> overrides) {
        return overrides.stream()
            .filter(IdOverride.class::isInstance)
            .map(o -> (IdOverride) o)
            .filter(o -> o.rootName().equals(rootName))
            .findFirst();
    }

    Optional<ForceSeparateRoot> findForceSeparateRootForChildRoot(
        DetectedRoot root,
        List<HierarchicalOverride> overrides
    ) {
        if (root.isPrimary() || root.parentFieldName() == null) {
            return Optional.empty();
        }

        return overrides.stream()
            .filter(ForceSeparateRoot.class::isInstance)
            .map(o -> (ForceSeparateRoot) o)
            .filter(o -> matchesParentField(o.fieldPath(), root.parentFieldName()))
            .findFirst();
    }

    private boolean matchesParentField(String fieldPath, String parentFieldName) {
        return fieldPath.equals(parentFieldName) || fieldPath.endsWith("." + parentFieldName);
    }
}
