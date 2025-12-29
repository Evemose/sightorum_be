package com.rorm.jpasupport;

import com.rorm.metamodel.AttributeLocation;
import com.rorm.metamodel.ReferenceAttribute.ReferenceMapping;

/**
 * Strategy interface for resolving physical database mappings (table and column names) from JPA metadata.
 * <p>
 * Different implementations can use different strategies:
 * <ul>
 *     <li>Annotation-based (JPA standard annotations)</li>
 *     <li>Hibernate-specific metadata (EntityPersister)</li>
 *     <li>Custom naming strategies</li>
 * </ul>
 */
public interface JpaPhysicalMappingResolver {

    /**
     * Resolves the physical location (table, column) for a basic-valued attribute path.
     * <p>
     * Supports various path patterns:
     * <ul>
     *     <li>{@code "firstName"} - direct basic attribute</li>
     *     <li>{@code "shippingAddress.city"} - embedded attribute field</li>
     *     <li>{@code "phoneNumbers"} - element collection (returns collection table + element column)</li>
     *     <li>{@code "items.productName"} - element collection embedded field</li>
     * </ul>
     *
     * @param entityType the entity class that owns the root attribute
     * @param attributePath dot-separated path to the target basic value
     * @return the physical location; column may be null for embeddable element collections
     */
    AttributeLocation resolveLocation(Class<?> entityType, String attributePath);

    /**
     * Resolves mapping strategy for a singular association (ManyToOne, OneToOne).
     *
     * @param entityType the entity class containing the association
     * @param attributeName name of the association attribute
     * @return either InverseRootTableColumn (for mappedBy) or JoinTableMapping (for owning side)
     */
    ReferenceMapping resolveSingularAssociation(Class<?> entityType, String attributeName);

    /**
     * Resolves mapping strategy for a plural association (OneToMany, ManyToMany).
     *
     * @param entityType the entity class containing the association
     * @param attributeName name of the association attribute
     * @return either InverseRootTableColumn (for mappedBy) or JoinTableMapping (for owning side)
     */
    ReferenceMapping resolvePluralAssociation(Class<?> entityType, String attributeName);

    /**
     * Gets the primary table name for an entity class.
     *
     * @param entityClass the entity class
     * @return the physical table name
     */
    String getPrimaryTableName(Class<?> entityClass);
}
