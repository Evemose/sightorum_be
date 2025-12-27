package com.rorm.metamodel;

import com.rorm.metamodel.Attribute.MapAttribute;
import com.rorm.metamodel.Attribute.PluralAttribute;
import com.rorm.metamodel.Attribute.SingularAttribute;

import java.util.Set;

public sealed interface Attribute permits SingularAttribute, PluralAttribute, MapAttribute {

    String name();

    Class<?> javaType();

    sealed interface SingularAttribute extends Attribute permits BasicAttribute, CompositeAttribute, ReferenceAttribute {
    }

    sealed interface PluralAttribute extends Attribute permits CollectionAttribute, PluralReferenceAttribute {
    }

    record BasicAttribute(
        String name,
        Class<?> javaType,
        AttributeLocation location
    ) implements SingularAttribute {
    }

    record CompositeAttribute(
        String name,
        Class<?> javaType,
        Set<Attribute> attributes
    ) implements SingularAttribute {
    }

    record ReferenceAttribute(
        String name,
        Class<?> javaType,
        AttributeLocation location
    ) implements SingularAttribute {
    }

    record CollectionAttribute(
        String name,
        Class<?> javaType,
        SingularAttribute elementType
    ) implements PluralAttribute {
    }

    record PluralReferenceAttribute(
        String name,
        Class<?> javaType,
        ReferenceAttribute elementType
    ) implements PluralAttribute {
    }

    record MapAttribute(
        String name,
        Class<?> javaType,
        SingularAttribute key,
        SingularAttribute value
    ) implements Attribute {
    }

}
