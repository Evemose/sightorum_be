package com.rorm.metamodel;

import com.rorm.metamodel.CollectionAttribute.CollectionElement;

/**
 * Common interface for anything that can be a target in a Path.
 * This includes named attributes, unnamed collection elements, and joined roots.
 */
public sealed interface PathTarget permits Attribute, CollectionElement, AliasedRoot {
}
