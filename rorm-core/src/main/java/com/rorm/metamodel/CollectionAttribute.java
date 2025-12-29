package com.rorm.metamodel;

import com.rorm.metamodel.Attribute.PluralAttribute;

import java.util.Set;

public record CollectionAttribute(
    String name,
    String tableName,
    CollectionElement elementType
) implements PluralAttribute {

    public sealed interface CollectionElement extends PathTarget permits BasicElement, CompositeElement {
    }

    public record BasicElement(
        AttributeLocation location,
        DataType dataType
    ) implements CollectionElement {
    }

    public record CompositeElement(
        Set<Attribute> attributes
    ) implements CollectionElement {
    }
}
