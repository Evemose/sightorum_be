package com.rorm.metamodel;

import com.rorm.metamodel.Attribute.SingularAttribute;

public record BasicAttribute(
    String name,
    AttributeLocation location,
    DataType dataType
) implements SingularAttribute {
}
