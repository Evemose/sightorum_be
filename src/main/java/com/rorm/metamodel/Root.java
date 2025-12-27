package com.rorm.metamodel;

import java.util.List;

public record Root(
    String primaryTableName,
    Class<?> javaType,
    List<Attribute> attributes
) {
}
