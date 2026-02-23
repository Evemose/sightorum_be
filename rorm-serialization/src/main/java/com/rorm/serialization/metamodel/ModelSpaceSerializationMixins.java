package com.rorm.serialization.metamodel;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;
import com.rorm.metamodel.*;
import com.rorm.metamodel.DataType.CategorcialType;

/**
 * Jackson mixins for ModelSpace serialization with full fidelity.
 * Includes cycle resolution via @JsonIdentityInfo and polymorphic type handling via @JsonSubTypes.
 * Unlike DTOs, this serializes everything including column mappings and internal structures.
 */
public class ModelSpaceSerializationMixins {

    @JsonIdentityInfo(generator = ObjectIdGenerators.UUIDGenerator.class)
    public abstract static class RootMixin {
    }

    // Attribute hierarchy with type information
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@type")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = BasicAttribute.class, name = "basic"),
        @JsonSubTypes.Type(value = CollectionAttribute.class, name = "collection"),
        @JsonSubTypes.Type(value = CompositeAttribute.class, name = "composite"),
        @JsonSubTypes.Type(value = SingularReferenceAttribute.class, name = "singularReference"),
        @JsonSubTypes.Type(value = PluralReferenceAttribute.class, name = "pluralReference")
    })
    public abstract static class AttributeMixin {
    }

    // DataType hierarchy with type information
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@type")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = DataType.NumericType.class, name = "numeric"),
        @JsonSubTypes.Type(value = DataType.StringType.class, name = "string"),
        @JsonSubTypes.Type(value = DataType.BooleanType.class, name = "boolean"),
        @JsonSubTypes.Type(value = DataType.DateType.class, name = "date"),
        @JsonSubTypes.Type(value = DataType.TimeType.class, name = "time"),
        @JsonSubTypes.Type(value = DataType.TimezoneType.class, name = "timezone"),
        @JsonSubTypes.Type(value = DataType.DateTimeType.class, name = "datetime"),
        @JsonSubTypes.Type(value = DataType.DayOfWeekType.class, name = "dayOfWeek"),
        @JsonSubTypes.Type(value = CategorcialType.class, name = "enum"),
        @JsonSubTypes.Type(value = DataType.ListType.class, name = "list")
    })
    public abstract static class DataTypeMixin {
    }

    // ReferenceMapping hierarchy with type information
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@type")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = ReferenceAttribute.InverseRootTableColumn.class, name = "inverseColumn"),
        @JsonSubTypes.Type(value = ReferenceAttribute.SameTableColumn.class, name = "sameTable"),
        @JsonSubTypes.Type(value = ReferenceAttribute.JoinTableMapping.class, name = "joinTable")
    })
    public abstract static class ReferenceMappingMixin {
    }

    // CollectionAttribute.ElementType hierarchy with type information
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@type")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = CollectionAttribute.BasicElement.class, name = "basicElement"),
        @JsonSubTypes.Type(value = CollectionAttribute.CompositeElement.class, name = "compositeElement")
    })
    public abstract static class CollectionElementTypeMixin {
    }
}
