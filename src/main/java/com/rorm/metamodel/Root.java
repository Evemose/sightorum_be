package com.rorm.metamodel;

import java.util.List;
import java.util.Objects;

public record Root(
    String primaryTableName,
    List<Attribute> attributes
) {

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Root root)) {
            return false;
        }
        return Objects.equals(primaryTableName, root.primaryTableName);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(primaryTableName);
    }
}
