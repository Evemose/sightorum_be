package com.rorm.metamodel;

import com.rorm.metamodel.Attribute.PluralAttribute;
import com.rorm.metamodel.Attribute.SingularAttribute;

public sealed interface Attribute extends PathTarget permits PluralAttribute, SingularAttribute, ReferenceAttribute {

    String name();

    sealed interface SingularAttribute extends Attribute permits BasicAttribute, CompositeAttribute, SingularReferenceAttribute {
    }

    sealed interface PluralAttribute extends Attribute permits CollectionAttribute, PluralReferenceAttribute {
    }


}
