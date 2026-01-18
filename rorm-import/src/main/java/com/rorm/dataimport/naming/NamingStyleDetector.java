package com.rorm.dataimport.naming;

import java.util.Collection;
import java.util.EnumSet;

public class NamingStyleDetector {

    public NamingStyle detect(Collection<String> propertyNames) {
        if (propertyNames.isEmpty()) {
            throw new IllegalArgumentException("Cannot detect naming style from empty property list");
        }

        var possibleStyles = EnumSet.allOf(NamingStyle.class);

        for (var name : propertyNames) {
            possibleStyles.removeIf(style -> !style.matches(name));

            if (possibleStyles.isEmpty()) {
                throw new IllegalStateException(
                    "No consistent naming style detected. Property names do not conform to a single style. Property: " + name
                );
            }
        }

        if (possibleStyles.size() > 1 && !propertyNames.stream().allMatch(s -> s.equals(s.toLowerCase()))) {
            throw new IllegalStateException(
                "Multiple naming styles detected: " + possibleStyles + ". Cannot determine single consistent style."
            );
        }

        return possibleStyles.iterator().next();
    }
}
