package com.rorm.metamodel;

import com.rorm.metamodel.Attribute.SingularAttribute;

import java.util.Set;

public record CompositeAttribute(
    String name,
    Set<Attribute> attributes
) implements SingularAttribute {
}
