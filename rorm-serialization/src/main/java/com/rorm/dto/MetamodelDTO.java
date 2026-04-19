package com.rorm.dto;

import com.fasterxml.jackson.annotation.*;

import java.util.List;
import java.util.Set;

public class MetamodelDTO {

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@type")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = BasicAttributeDTO.class, name = "basic"),
        @JsonSubTypes.Type(value = CompositeAttributeDTO.class, name = "composite"),
        @JsonSubTypes.Type(value = SingularReferenceAttributeDTO.class, name = "singularRef"),
        @JsonSubTypes.Type(value = PluralReferenceAttributeDTO.class, name = "pluralRef"),
        @JsonSubTypes.Type(value = CollectionAttributeDTO.class, name = "collection")
    })
    @JsonClassDescription("Base interface for all attribute types in the metamodel")
    public sealed interface AttributeDTO permits
        BasicAttributeDTO, CompositeAttributeDTO, SingularReferenceAttributeDTO,
        PluralReferenceAttributeDTO, CollectionAttributeDTO {

        @JsonPropertyDescription("Name of the attribute in the domain model. Example: 'email', 'createdAt', 'address'")
        @JsonProperty(required = true)
        String name();
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@type")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = BasicElementDTO.class, name = "basic"),
        @JsonSubTypes.Type(value = CompositeElementDTO.class, name = "composite")
    })
    @JsonClassDescription("Base interface for collection element types")
    public sealed interface CollectionElementDTO permits BasicElementDTO, CompositeElementDTO {}

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@type")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = NumericTypeDTO.class, name = "numeric"),
        @JsonSubTypes.Type(value = StringTypeDTO.class, name = "string"),
        @JsonSubTypes.Type(value = BooleanTypeDTO.class, name = "boolean"),
        @JsonSubTypes.Type(value = DateTypeDTO.class, name = "date"),
        @JsonSubTypes.Type(value = TimeTypeDTO.class, name = "time"),
        @JsonSubTypes.Type(value = TimezoneTypeDTO.class, name = "timezone"),
        @JsonSubTypes.Type(value = DateTimeTypeDTO.class, name = "datetime"),
        @JsonSubTypes.Type(value = DayOfWeekTypeDTO.class, name = "dayOfWeek"),
        @JsonSubTypes.Type(value = EnumTypeDTO.class, name = "enum"),
        @JsonSubTypes.Type(value = ListTypeDTO.class, name = "list"),
        @JsonSubTypes.Type(value = IntervalTypeDTO.class, name = "interval")
    })
    @JsonClassDescription("Base interface for all data types")
    public sealed interface DataTypeDTO permits
        NumericTypeDTO, StringTypeDTO, BooleanTypeDTO, DateTypeDTO, TimeTypeDTO,
        TimezoneTypeDTO, DateTimeTypeDTO, DayOfWeekTypeDTO, EnumTypeDTO, ListTypeDTO,
        IntervalTypeDTO {}

    @JsonIdentityInfo(generator = ObjectIdGenerators.StringIdGenerator.class)
    @JsonClassDescription("Container for all root entities in the metamodel, representing the complete data model")
    public record ModelSpaceDTO(
        @JsonPropertyDescription("Set of all root entities in the model. Each root represents a top-level entity. Cannot be null but may be empty.")
        @JsonProperty(required = true)
        Set<RootDTO> roots
    ) {}

    @JsonClassDescription("Represents a root entity in the metamodel with a primary key")
    public record RootDTO(
        @JsonPropertyDescription("Name of the root entity (typically the primary table name). Example: 'users', 'orders', 'products'")
        @JsonProperty(required = true)
        String name,

        @JsonPropertyDescription("List of attributes belonging to this entity. Can include basic attributes, composite attributes, references, and collections. Cannot be null but may be empty.")
        @JsonProperty(required = true)
        List<AttributeDTO> attributes,

        @JsonPropertyDescription("Descriptor for the primary key of this entity. Specifies the ID attribute and its configuration. Example: IdDescriptor with NumericType(19,0)")
        @JsonProperty(required = true)
        IdDescriptorDTO idDescriptor
    ) {}

    @JsonClassDescription("Descriptor for the primary key of an entity, encapsulating ID attribute configuration")
    public record IdDescriptorDTO(
        @JsonPropertyDescription("The attribute serving as the primary key. Typically named 'id' with NumericType or StringType. Example: BasicAttribute('id', NumericType(19,0))")
        @JsonProperty(required = true)
        BasicAttributeDTO idAttribute
    ) {}

    @JsonClassDescription("A basic attribute with a primitive or simple data type")
    public record BasicAttributeDTO(
        @JsonPropertyDescription("Name of the attribute. Example: 'id', 'email', 'price'")
        @JsonProperty(required = true)
        String name,

        @JsonPropertyDescription("Data type specification for this attribute. Examples: NumericType(10,2) for decimal, StringType() for text, DateTimeType() for timestamps")
        @JsonProperty(required = true)
        DataTypeDTO dataType
    ) implements AttributeDTO {}

    @JsonClassDescription("A composite attribute composed of multiple sub-attributes, representing an embedded value object")
    public record CompositeAttributeDTO(
        @JsonPropertyDescription("Name of the composite attribute. Example: 'address', 'money', 'dimensions'")
        @JsonProperty(required = true)
        String name,

        @JsonPropertyDescription("Set of nested attributes that make up this composite. Example: address with {street, city, zipCode}. Cannot be null, must contain at least one attribute.")
        @JsonProperty(required = true)
        Set<AttributeDTO> attributes
    ) implements AttributeDTO {}

    @JsonClassDescription("A singular reference attribute representing a many-to-one or one-to-one relationship to another entity")
    public record SingularReferenceAttributeDTO(
        @JsonPropertyDescription("Name of the reference attribute. Example: 'customer', 'primaryAddress', 'assignedUser'")
        @JsonProperty(required = true)
        String name,

        @JsonPropertyDescription("Name of the target root entity being referenced. Example: 'customers', 'users', 'addresses'")
        @JsonProperty(required = true)
        String targetRootName
    ) implements AttributeDTO {}

    @JsonClassDescription("A plural reference attribute representing a one-to-many or many-to-many relationship to a collection of entities")
    public record PluralReferenceAttributeDTO(
        @JsonPropertyDescription("Name of the collection reference. Example: 'orders', 'addresses', 'comments'")
        @JsonProperty(required = true)
        String name,

        @JsonPropertyDescription("Name of the target root entity type for elements in this collection. Example: 'orders', 'addresses'")
        @JsonProperty(required = true)
        String targetRootName
    ) implements AttributeDTO {}

    @JsonClassDescription("A collection attribute representing a collection of value objects (not entities)")
    public record CollectionAttributeDTO(
        @JsonPropertyDescription("Name of the collection attribute. Example: 'tags', 'phoneNumbers', 'orderItems'")
        @JsonProperty(required = true)
        String name,

        @JsonPropertyDescription("Type specification for collection elements. Can be BasicElement for simple values or CompositeElement for structured values")
        @JsonProperty(required = true)
        CollectionElementDTO elementType
    ) implements AttributeDTO {}

    @JsonClassDescription("A basic collection element representing a simple value (primitive or simple type) in a collection")
    public record BasicElementDTO(
        @JsonPropertyDescription("Data type of the collection element. Example: StringType() for a tag, NumericType(10,2) for a price list")
        @JsonProperty(required = true)
        DataTypeDTO dataType
    ) implements CollectionElementDTO {}

    @JsonClassDescription("A composite collection element representing a structured value object in a collection")
    public record CompositeElementDTO(
        @JsonPropertyDescription("Set of attributes composing each element in the collection. Example: order line items with {product, quantity, price}. Cannot be null, must contain at least one attribute.")
        @JsonProperty(required = true)
        Set<AttributeDTO> attributes
    ) implements CollectionElementDTO {}

    @JsonClassDescription("Numeric data type with configurable precision and scale for exact decimal arithmetic")
    public record NumericTypeDTO(
        @JsonPropertyDescription("Total number of digits that can be stored. Example: 19 for BIGINT, 10 for standard INT, 15 for financial calculations")
        @JsonProperty(required = true)
        int precision,

        @JsonPropertyDescription("Number of digits after the decimal point. Example: 0 for integers, 2 for currency (dollars.cents), 4 for unit prices")
        @JsonProperty(required = true)
        int scale
    ) implements DataTypeDTO {}

    @JsonClassDescription("String/text data type for character data")
    public record StringTypeDTO() implements DataTypeDTO {}

    @JsonClassDescription("Boolean data type for true/false values")
    public record BooleanTypeDTO() implements DataTypeDTO {}

    @JsonClassDescription("Date data type for calendar dates without time")
    public record DateTypeDTO() implements DataTypeDTO {}

    @JsonClassDescription("Time data type for time of day without date")
    public record TimeTypeDTO() implements DataTypeDTO {}

    @JsonClassDescription("Timezone data type for timezone identifiers")
    public record TimezoneTypeDTO() implements DataTypeDTO {}

    @JsonClassDescription("DateTime data type for timestamps with date and time")
    public record DateTimeTypeDTO() implements DataTypeDTO {}

    @JsonClassDescription("Day of week data type for weekday values")
    public record DayOfWeekTypeDTO() implements DataTypeDTO {}

    @JsonClassDescription("Enumeration data type with a fixed set of allowed string values")
    public record EnumTypeDTO(
        @JsonPropertyDescription("Array of allowed enum values. Example: ['PENDING', 'PROCESSING', 'COMPLETED', 'CANCELLED'] for order status. Cannot be null or empty.")
        @JsonProperty(required = true)
        String[] values
    ) implements DataTypeDTO {}

    @JsonClassDescription("Interval data type for time duration values (used in date arithmetic)")
    public record IntervalTypeDTO() implements DataTypeDTO {}

    @JsonClassDescription("List data type for array/list of values")
    public record ListTypeDTO(
        @JsonPropertyDescription("Data type of list elements. Example: StringType() for a list of tags, NumericType(10,0) for a list of IDs")
        @JsonProperty(required = true)
        DataTypeDTO elementType
    ) implements DataTypeDTO {}
}
