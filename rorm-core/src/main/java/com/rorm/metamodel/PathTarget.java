package com.rorm.metamodel;

import com.rorm.metamodel.CollectionAttribute.CollectionElement;

/**
 * Common interface for anything that can be a target in a Path.
 * This includes both named attributes and unnamed collection elements.
 */
public sealed interface PathTarget permits Attribute, CollectionElement {
}
