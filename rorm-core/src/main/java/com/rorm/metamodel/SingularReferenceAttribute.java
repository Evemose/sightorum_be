package com.rorm.metamodel;

import com.rorm.metamodel.Attribute.SingularAttribute;

public record SingularReferenceAttribute(
    String name,
    Root targetRoot,
    ReferenceMapping mappingStrategy
) implements SingularAttribute, ReferenceAttribute {
}
