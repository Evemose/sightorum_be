package com.rorm.metamodel;

import com.rorm.metamodel.Attribute.PluralAttribute;

public record PluralReferenceAttribute(
    String name,
    Root targetRoot,
    ReferenceMapping mappingStrategy
) implements PluralAttribute, ReferenceAttribute {
}
