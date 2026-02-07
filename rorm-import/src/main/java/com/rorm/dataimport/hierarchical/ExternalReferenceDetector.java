package com.rorm.dataimport.hierarchical;

import com.rorm.dataimport.attribute.NameUtils;

import java.util.Optional;
import java.util.Set;

/**
 * Detects if a field name represents a reference to an external root.
 * For example, "user_id" or "userId" would match "users" root.
 */
class ExternalReferenceDetector {

    /**
     * Detects if a field name represents a reference to an external root.
     *
     * @param fieldName       The field name to check (e.g., "user_id", "userId")
     * @param allRootNames    All available root names
     * @param currentRootName The current root name (to skip self-references)
     * @return The target root name if a match is found, otherwise empty
     */
    Optional<String> detectExternalReference(String fieldName, Set<String> allRootNames, String currentRootName) {
        return extractPrefix(fieldName)
            .flatMap(prefix -> findMatchingRoot(prefix, allRootNames, currentRootName));
    }

    private Optional<String> extractPrefix(String fieldName) {
        if (fieldName.endsWith("_id")) {
            return Optional.of(fieldName.substring(0, fieldName.length() - 3));
        }
        if (fieldName.endsWith("Id") && fieldName.length() > 2) {
            return Optional.of(fieldName.substring(0, fieldName.length() - 2));
        }
        return Optional.empty();
    }

    private Optional<String> findMatchingRoot(String prefix, Set<String> allRootNames, String currentRootName) {
        for (var rootName : allRootNames) {
            if (rootName.equals(currentRootName)) {
                continue;
            }

            if (matchesRoot(prefix, rootName)) {
                return Optional.of(rootName);
            }
        }
        return Optional.empty();
    }

    private boolean matchesRoot(String prefix, String rootName) {
        var singularRoot = NameUtils.singularize(rootName);
        return singularRoot.equalsIgnoreCase(prefix) || rootName.equalsIgnoreCase(prefix);
    }
}
