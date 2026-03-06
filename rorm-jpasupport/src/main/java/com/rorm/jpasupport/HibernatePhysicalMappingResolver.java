package com.rorm.jpasupport;

import com.rorm.metamodel.AttributeLocation;
import com.rorm.metamodel.ReferenceAttribute.InverseRootTableColumn;
import com.rorm.metamodel.ReferenceAttribute.JoinTableMapping;
import com.rorm.metamodel.ReferenceAttribute.ReferenceMapping;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.metamodel.MappingMetamodel;
import org.hibernate.metamodel.mapping.*;
import org.hibernate.metamodel.mapping.internal.ToOneAttributeMapping;
import org.hibernate.persister.collection.AbstractCollectionPersister;
import org.hibernate.persister.entity.EntityPersister;
import org.springframework.stereotype.Component;

@Component
public class HibernatePhysicalMappingResolver implements JpaPhysicalMappingResolver {

    private final MappingMetamodel mappingMetamodel;

    public HibernatePhysicalMappingResolver(EntityManagerFactory emf) {
        var sessionFactory = emf.unwrap(SessionFactoryImplementor.class);
        this.mappingMetamodel = sessionFactory.getMappingMetamodel();
    }

    @Override
    public AttributeLocation resolveLocation(Class<?> entityType, String attributePath) {
        var persister = getEntityPersister(entityType);
        var parts = attributePath.split("\\.");

        // Handle identifier specially
        if (parts.length == 1 && "id".equals(parts[0])) {
            var idPart = expectBasic(persister.getIdentifierMapping(), entityType, "identifier");
            return locationFrom(idPart);
        }

        // Navigate to root attribute
        var rootAttr = persister.findAttributeMapping(parts[0]);
        if (rootAttr == null) {
            throw mappingError(entityType, parts[0], "attribute not found");
        }

        return switch (rootAttr) {
            case BasicValuedModelPart basic when parts.length == 1 -> locationFrom(basic);
            case EmbeddableValuedModelPart embedded -> resolveEmbeddedPath(embedded, parts, 1, entityType);
            case PluralAttributeMapping plural -> resolveCollectionPath(plural, parts, entityType);
            default -> throw mappingError(entityType, attributePath,
                "unsupported attribute type: " + rootAttr.getClass().getSimpleName());
        };
    }

    @Override
    public ReferenceMapping resolveSingularAssociation(Class<?> entityType, String attributeName) {
        var persister = getEntityPersister(entityType);
        var mapping = expectType(
            persister.findAttributeMapping(attributeName),
            ToOneAttributeMapping.class, entityType, attributeName
        );

        var fkDescriptor = mapping.getForeignKeyDescriptor();
        if (fkDescriptor == null) {
            throw mappingError(entityType, attributeName, "no FK descriptor");
        }

        var keyPart = expectBasic(fkDescriptor.getKeyPart(), entityType, attributeName + " FK key");
        var ownerTable = extractTableName(persister.getMappedTableDetails().getTableName());
        var fkTable = extractTableName(keyPart.getContainingTableExpression());

        if (ownerTable.equals(fkTable)) {
            return new JoinTableMapping(locationFrom(keyPart), "id");
        }
        return new InverseRootTableColumn(keyPart.getSelectionExpression());
    }

    private EntityPersister getEntityPersister(Class<?> entityClass) {
        return mappingMetamodel.getEntityDescriptor(entityClass);
    }

    private <T> T expectType(Object mapping, Class<T> type, Class<?> entityType, String context) {
        if (!type.isInstance(mapping)) {
            throw mappingError(entityType, context, "expected " + type.getSimpleName() + ", got: " +
                                                    (mapping != null ? mapping.getClass().getSimpleName() : "null"));
        }
        return type.cast(mapping);
    }

    // --- Path navigation helpers ---

    private IllegalStateException mappingError(Class<?> entityType, String context, String message) {
        return new IllegalStateException(
            "Mapping error for '%s' on %s: %s".formatted(context, entityType.getSimpleName(), message)
        );
    }

    private BasicValuedModelPart expectBasic(Object mapping, Class<?> entityType, String context) {
        return expectType(mapping, BasicValuedModelPart.class, entityType, context);
    }

    private String extractTableName(String qualifiedName) {
        if (qualifiedName == null) {
            throw new IllegalStateException("Table name cannot be null");
        }
        var dotIndex = qualifiedName.lastIndexOf('.');
        return dotIndex >= 0 ? qualifiedName.substring(dotIndex + 1) : qualifiedName;
    }

    private AttributeLocation locationFrom(BasicValuedModelPart part) {
        return new AttributeLocation(
            extractTableName(part.getContainingTableExpression()),
            part.getSelectionExpression()
        );
    }

    @Override
    public ReferenceMapping resolvePluralAssociation(Class<?> entityType, String attributeName) {
        var persister = getEntityPersister(entityType);
        var plural = expectType(
            persister.findAttributeMapping(attributeName),
            PluralAttributeMapping.class, entityType, attributeName
        );

        var acp = expectCollectionPersister(plural, entityType, attributeName);
        var keyPart = expectBasic(
            plural.getKeyDescriptor().getKeyPart(),
            entityType, attributeName + " collection key"
        );

        var collectionTable = extractTableName(acp.getTableName());
        var targetTable = resolveTargetTableForPluralAssociation(plural);

        if (collectionTable.equals(targetTable)) {
            return new InverseRootTableColumn(keyPart.getSelectionExpression());
        }

        var inverseColumn = resolveInverseJoinColumn(plural);
        return new JoinTableMapping(
            new AttributeLocation(collectionTable, keyPart.getSelectionExpression()),
            inverseColumn
        );
    }

    private AbstractCollectionPersister expectCollectionPersister(
        PluralAttributeMapping plural, Class<?> entityType, String attrName
    ) {
        var descriptor = plural.getCollectionDescriptor();
        if (!(descriptor instanceof AbstractCollectionPersister acp)) {
            throw mappingError(entityType, attrName,
                "expected AbstractCollectionPersister, got: " + descriptor.getClass().getSimpleName());
        }
        return acp;
    }

    private String resolveTargetTableForPluralAssociation(PluralAttributeMapping plural) {
        var element = plural.getElementDescriptor();
        if (element instanceof EntityValuedModelPart entityPart) {
            var entityType = entityPart.getEntityMappingType();
            return extractTableName(entityType.getMappedTableDetails().getTableName());
        }
        return null;
    }

    private String resolveInverseJoinColumn(PluralAttributeMapping plural) {
        var element = plural.getElementDescriptor();
        if (element instanceof Association association) {
            var fk = association.getForeignKeyDescriptor();
            if (fk != null && fk.getKeyPart() instanceof BasicValuedModelPart keyPart) {
                return keyPart.getSelectionExpression();
            }
        }
        return "id";
    }

    @Override
    public String getPrimaryTableName(Class<?> entityClass) {
        var persister = getEntityPersister(entityClass);
        return extractTableName(persister.getMappedTableDetails().getTableName());
    }

    private AttributeLocation resolveEmbeddedPath(
        EmbeddableValuedModelPart embedded, String[] parts, int index, Class<?> entityType
    ) {
        if (index >= parts.length) {
            throw mappingError(entityType, String.join(".", parts), "path ends at embeddable, not basic");
        }

        var subPart = embedded.findSubPart(parts[index], null);
        if (subPart == null) {
            throw mappingError(entityType, parts[index], "sub-part not found in embeddable");
        }

        return switch (subPart) {
            case BasicValuedModelPart basic -> locationFrom(basic);
            case EmbeddableValuedModelPart nested -> resolveEmbeddedPath(nested, parts, index + 1, entityType);
            default -> throw mappingError(entityType, String.join(".", parts),
                "unsupported sub-part type: " + subPart.getClass().getSimpleName());
        };
    }

    private AttributeLocation resolveCollectionPath(
        PluralAttributeMapping plural, String[] parts, Class<?> entityType
    ) {
        var acp = expectCollectionPersister(plural, entityType, parts[0]);
        var tableName = extractTableName(acp.getTableName());
        var element = plural.getElementDescriptor();

        if (parts.length == 1) {
            // Just the collection itself - return table + element column (or null for embeddable)
            return switch (element) {
                case BasicValuedModelPart basic -> new AttributeLocation(tableName, basic.getSelectionExpression());
                case EmbeddableValuedModelPart _ -> new AttributeLocation(tableName, null);
                default -> throw mappingError(entityType, parts[0],
                    "unsupported element type: " + element.getClass().getSimpleName());
            };
        }

        // Navigate into embeddable element
        if (!(element instanceof EmbeddableValuedModelPart embeddedElement)) {
            throw mappingError(entityType, String.join(".", parts), "cannot navigate into non-embeddable element");
        }

        var nestedPart = embeddedElement.findSubPart(parts[1], null);
        if (nestedPart == null) {
            throw mappingError(entityType, parts[1], "sub-part not found in collection element");
        }

        return switch (nestedPart) {
            case BasicValuedModelPart basic -> new AttributeLocation(tableName, basic.getSelectionExpression());
            case EmbeddableValuedModelPart nested -> {
                // Further nesting - find the basic at the end
                var location = resolveEmbeddedPath(nested, parts, 2, entityType);
                yield new AttributeLocation(tableName, location.column());
            }
            default -> throw mappingError(entityType, String.join(".", parts),
                "unsupported nested element type: " + nestedPart.getClass().getSimpleName());
        };
    }
}
