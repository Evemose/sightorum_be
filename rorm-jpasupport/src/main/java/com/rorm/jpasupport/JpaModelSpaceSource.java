package com.rorm.jpasupport;

import com.rorm.metamodel.*;
import com.rorm.metamodel.CollectionAttribute.BasicElement;
import com.rorm.metamodel.CollectionAttribute.CompositeElement;
import com.rorm.metamodel.DataType.CategorcialType;
import com.rorm.metamodel.DataType.NumericType;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.Attribute.PersistentAttributeType;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.MapAttribute;
import jakarta.persistence.metamodel.PluralAttribute;
import jakarta.persistence.metamodel.SingularAttribute;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

import static jakarta.persistence.metamodel.Attribute.PersistentAttributeType.*;

@Component
@RequiredArgsConstructor
public class JpaModelSpaceSource {

    private static final ScopedValue<Map<Class<?>, Root>> rootsByTypeScope = ScopedValue.newInstance();

    private static final Set<PersistentAttributeType> REFERENCE_TYPES =
        Set.of(MANY_TO_ONE, ONE_TO_ONE, ONE_TO_MANY, MANY_TO_MANY);
    private final JpaPhysicalMappingResolver mappingResolver;
    private final EntityManagerFactory emf;

    /**
     * Extracts RORM ModelSpace from the injected EntityManagerFactory.
     */
    public ModelSpace extractFromInjectedEMF() {
        var rootsByType = new HashMap<Class<?>, Root>();
        return ScopedValue.where(rootsByTypeScope, rootsByType).call(() -> {
            initRoots();
            completeRoots();
            return new ModelSpace(freeze(rootsByType.values()));
        });
    }

    private void initRoots() {
        var jpaMetamodel = emf.getMetamodel();
        var rootsByType = rootsByTypeScope.get();
        for (var entityType : jpaMetamodel.getEntities()) {
            var tableName = mappingResolver.getPrimaryTableName(entityType.getJavaType());
            var idDescriptor = createIdDescriptor(entityType);
            var root = new Root(tableName, new ArrayList<>(), idDescriptor);
            rootsByType.put(entityType.getJavaType(), root);
        }
    }

    private IdDescriptor createIdDescriptor(EntityType<?> entityType) {
        var idType = entityType.getIdType();
        var javaType = idType.getJavaType();
        var idAttr = entityType.getId(javaType);
        var location = mappingResolver.resolveLocation(entityType.getJavaType(), idAttr.getName());
        var dataType = inferDataType(javaType);
        return new IdDescriptor(new BasicAttribute(idAttr.getName(), location, dataType));
    }

    private Set<Root> freeze(Collection<Root> completeRoots) {
        return completeRoots.stream()
            .map(root -> new Root(
                root.primaryTableName(),
                List.copyOf(root.attributes()),
                root.idDescriptor()
            )).collect(Collectors.toUnmodifiableSet());
    }

    private void completeRoots() {
        var jpaMetamodel = emf.getMetamodel();
        var rootsByType = rootsByTypeScope.get();

        for (var entityType : jpaMetamodel.getEntities()) {
            var ownerType = entityType.getJavaType();
            var root = rootsByType.get(ownerType);
            var attributes = entityType.getAttributes().stream()
                .map(jpaAttr ->
                    REFERENCE_TYPES.contains(jpaAttr.getPersistentAttributeType()) ?
                        convertReferenceAttribute(jpaAttr, ownerType) :
                        convertNonReferenceAttribute(jpaAttr, ownerType)
                ).collect(Collectors.toUnmodifiableSet());
            root.attributes().addAll(attributes);
        }
    }

    /**
     * Converts non-reference attributes (basic, embedded, element collections).
     */
    private Attribute convertNonReferenceAttribute(
        jakarta.persistence.metamodel.Attribute<?, ?> jpaAttr,
        Class<?> ownerType
    ) {
        return switch (jpaAttr.getPersistentAttributeType()) {
            case BASIC -> convertBasicAttribute((SingularAttribute<?, ?>) jpaAttr, ownerType);
            case EMBEDDED -> convertEmbeddedAttribute((SingularAttribute<?, ?>) jpaAttr, ownerType);
            case ELEMENT_COLLECTION -> convertElementCollection((PluralAttribute<?, ?, ?>) jpaAttr, ownerType);
            default ->
                throw new IllegalStateException("Unexpected attribute type: " + jpaAttr.getPersistentAttributeType());
        };
    }

    /**
     * Converts reference attributes (ManyToOne, OneToOne, OneToMany, ManyToMany).
     */
    private Attribute convertReferenceAttribute(
        jakarta.persistence.metamodel.Attribute<?, ?> jpaAttr,
        Class<?> ownerType
    ) {
        var rootsByType = rootsByTypeScope.get();
        return switch (jpaAttr.getPersistentAttributeType()) {
            case MANY_TO_ONE, ONE_TO_ONE -> {
                var singularAttr = (SingularAttribute<?, ?>) jpaAttr;
                var targetRoot = rootsByType.get(singularAttr.getJavaType());
                var mapping = mappingResolver.resolveSingularAssociation(ownerType, singularAttr.getName());
                yield new SingularReferenceAttribute(singularAttr.getName(), targetRoot, mapping);
            }
            case ONE_TO_MANY, MANY_TO_MANY -> {
                var pluralAttr = (PluralAttribute<?, ?, ?>) jpaAttr;
                var targetRoot = rootsByType.get(pluralAttr.getElementType().getJavaType());
                var mapping = mappingResolver.resolvePluralAssociation(ownerType, pluralAttr.getName());
                yield new PluralReferenceAttribute(pluralAttr.getName(), targetRoot, mapping);
            }
            default ->
                throw new IllegalStateException("Unexpected attribute type: " + jpaAttr.getPersistentAttributeType());
        };
    }

    /**
     * Converts basic attributes (primitives, strings, enums, dates, etc.)
     */
    private BasicAttribute convertBasicAttribute(
        SingularAttribute<?, ?> jpaAttr,
        Class<?> ownerType
    ) {
        var location = mappingResolver.resolveLocation(ownerType, jpaAttr.getName());
        var dataType = inferDataType(jpaAttr.getJavaType());
        return new BasicAttribute(jpaAttr.getName(), location, dataType);
    }

    @SuppressWarnings("java:S3776")
    private DataType inferDataType(Class<?> javaType) {
        if (javaType == String.class) {
            return new DataType.StringType();
        } else if (javaType == Boolean.class || javaType == boolean.class) {
            return new DataType.BooleanType();
        } else if (Number.class.isAssignableFrom(javaType) || javaType.isPrimitive()) {
            return new NumericType(19, 0);
        } else if (javaType == LocalDate.class || javaType == Date.class) {
            return new DataType.DateType();
        } else if (javaType == LocalTime.class || javaType == Time.class) {
            return new DataType.TimeType();
        } else if (javaType == ZoneId.class || javaType == ZoneOffset.class) {
            return new DataType.TimezoneType();
        } else if (javaType == LocalDateTime.class || javaType == Timestamp.class ||
                   javaType == ZonedDateTime.class || javaType == OffsetDateTime.class ||
                   javaType == Instant.class) {
            return new DataType.DateTimeType();
        } else if (javaType == DayOfWeek.class) {
            return new DataType.DayOfWeekType();
        } else if (javaType.isEnum()) {
            var enumConstants = javaType.getEnumConstants();
            var values = Arrays.stream(enumConstants).map(Object::toString).toArray(String[]::new);
            return new CategorcialType(values);
        } else {
            return new DataType.StringType();
        }
    }

    /**
     * Converts embedded attributes (embeddables/composites)
     */
    private CompositeAttribute convertEmbeddedAttribute(
        SingularAttribute<?, ?> jpaAttr,
        Class<?> ownerType
    ) {
        var name = jpaAttr.getName();
        var javaType = jpaAttr.getJavaType();
        var embeddableType = emf.getMetamodel().embeddable(javaType);

        var nestedAttributes = embeddableType.getAttributes().stream()
            .map(nestedAttr -> convertEmbeddableAttribute(nestedAttr, javaType, ownerType, name))
            .collect(Collectors.toSet());

        return new CompositeAttribute(name, nestedAttributes);
    }

    /**
     * Converts attributes within an embeddable
     */
    private Attribute convertEmbeddableAttribute(
        jakarta.persistence.metamodel.Attribute<?, ?> jpaAttr,
        Class<?> embeddableType,
        Class<?> ownerType,
        String embeddableFieldPath
    ) {
        return switch (jpaAttr.getPersistentAttributeType()) {
            case BASIC -> {
                var singularAttr = (SingularAttribute<?, ?>) jpaAttr;
                var path = embeddableFieldPath + "." + jpaAttr.getName();
                var location = mappingResolver.resolveLocation(ownerType, path);
                var dataType = inferDataType(singularAttr.getJavaType());
                yield new BasicAttribute(jpaAttr.getName(), location, dataType);
            }
            case EMBEDDED -> {
                var singularAttr = (SingularAttribute<?, ?>) jpaAttr;
                var javaType = singularAttr.getJavaType();
                var nestedEmbeddable = emf.getMetamodel().embeddable(javaType);
                var nestedPath = embeddableFieldPath + "." + singularAttr.getName();

                var nestedAttributes = nestedEmbeddable.getAttributes().stream()
                    .map(nestedAttr -> convertEmbeddableAttribute(nestedAttr, javaType, ownerType, nestedPath))
                    .collect(Collectors.toSet());

                yield new CompositeAttribute(singularAttr.getName(), nestedAttributes);
            }
            case MANY_TO_ONE, ONE_TO_ONE -> {
                var singularAttr = (SingularAttribute<?, ?>) jpaAttr;
                var targetRoot = rootsByTypeScope.get().get(singularAttr.getJavaType());
                // For embeddable associations, get the FK column from the embedded path
                var path = embeddableFieldPath + "." + singularAttr.getName();
                var location = mappingResolver.resolveLocation(ownerType, path);
                // Embedded associations are always on the owning side (FK in owner table)
                var mapping = new ReferenceAttribute.JoinTableMapping(location, "id");
                yield new SingularReferenceAttribute(singularAttr.getName(), targetRoot, mapping);
            }
            case ONE_TO_MANY, MANY_TO_MANY -> {
                var pluralAttr = (PluralAttribute<?, ?, ?>) jpaAttr;
                var targetRoot = rootsByTypeScope.get().get(pluralAttr.getElementType().getJavaType());
                var path = embeddableFieldPath + "." + pluralAttr.getName();
                var location = mappingResolver.resolveLocation(embeddableType, path);
                // Plural associations in embeddables use join table
                var mapping = new ReferenceAttribute.JoinTableMapping(location, "id");
                yield new PluralReferenceAttribute(pluralAttr.getName(), targetRoot, mapping);
            }
            case ELEMENT_COLLECTION -> throw new UnsupportedOperationException(
                "Element collections within embeddables are not currently supported: " + jpaAttr.getName());
        };
    }

    /**
     * Converts element collections
     */
    private CollectionAttribute convertElementCollection(
        PluralAttribute<?, ?, ?> jpaAttr,
        Class<?> ownerType
    ) {
        var name = jpaAttr.getName();
        var jpaMetamodel = emf.getMetamodel();

        if (jpaAttr instanceof MapAttribute<?, ?, ?>) {
            throw new UnsupportedOperationException("Map element collections are not currently supported: " + name);
        }

        var location = mappingResolver.resolveLocation(ownerType, name);
        var elementTypeInfo = jpaAttr.getElementType();

        var element = switch (elementTypeInfo.getPersistenceType()) {
            case BASIC -> {
                var dataType = inferDataType(elementTypeInfo.getJavaType());
                yield new BasicElement(location, dataType);
            }
            case EMBEDDABLE -> {
                var elementType = elementTypeInfo.getJavaType();
                var embeddableType = jpaMetamodel.embeddable(elementType);

                var nestedAttributes = embeddableType.getAttributes().stream()
                    .map(nestedAttr -> convertElementCollectionEmbeddableAttribute(
                        nestedAttr, name, ownerType))
                    .collect(Collectors.toSet());

                yield new CompositeElement(nestedAttributes);
            }
            default -> throw new IllegalStateException(
                "Unexpected element type in element collection: " + elementTypeInfo.getPersistenceType());
        };

        return new CollectionAttribute(name, location.table(), element);
    }

    /**
     * Converts attributes within an element collection embeddable.
     */
    private Attribute convertElementCollectionEmbeddableAttribute(
        jakarta.persistence.metamodel.Attribute<?, ?> jpaAttr,
        String collectionName,
        Class<?> ownerType
    ) {
        return switch (jpaAttr.getPersistentAttributeType()) {
            case BASIC -> {
                var singularAttr = (SingularAttribute<?, ?>) jpaAttr;
                var path = collectionName + "." + jpaAttr.getName();
                var location = mappingResolver.resolveLocation(ownerType, path);
                var dataType = inferDataType(singularAttr.getJavaType());
                yield new BasicAttribute(jpaAttr.getName(), location, dataType);
            }
            case EMBEDDED -> {
                var singularAttr = (SingularAttribute<?, ?>) jpaAttr;
                var javaType = singularAttr.getJavaType();
                var nestedEmbeddable = emf.getMetamodel().embeddable(javaType);
                var nestedPath = collectionName + "." + singularAttr.getName();

                var nestedAttributes = nestedEmbeddable.getAttributes().stream()
                    .map(nestedAttr -> convertElementCollectionEmbeddableAttribute(
                        nestedAttr, nestedPath, ownerType))
                    .collect(Collectors.toSet());

                yield new CompositeAttribute(singularAttr.getName(), nestedAttributes);
            }
            default -> throw new UnsupportedOperationException(
                "Only BASIC and EMBEDDED attributes are supported within element collection embeddables: " +
                jpaAttr.getName() + " (" + jpaAttr.getPersistentAttributeType() + ")");
        };
    }
}
