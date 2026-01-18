package com.rorm.mapper;

import com.rorm.dto.MetamodelDTO.*;
import com.rorm.metamodel.*;
import com.rorm.metamodel.CollectionAttribute.BasicElement;
import com.rorm.metamodel.CollectionAttribute.CollectionElement;
import com.rorm.metamodel.CollectionAttribute.CompositeElement;
import com.rorm.metamodel.DataType.*;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.SubclassMapping;

import java.util.List;
import java.util.Set;

@Mapper
public interface MetamodelMapper {

    // ModelSpace
    ModelSpaceDTO toDTO(ModelSpace modelSpace);

    // Root
    @Mapping(target = "name", source = "primaryTableName")
    RootDTO toDTO(Root root);

    // IdDescriptor
    IdDescriptorDTO toDTO(IdDescriptor idDescriptor);

    // Attributes
    @SubclassMapping(source = BasicAttribute.class, target = BasicAttributeDTO.class)
    @SubclassMapping(source = CompositeAttribute.class, target = CompositeAttributeDTO.class)
    @SubclassMapping(source = SingularReferenceAttribute.class, target = SingularReferenceAttributeDTO.class)
    @SubclassMapping(source = PluralReferenceAttribute.class, target = PluralReferenceAttributeDTO.class)
    @SubclassMapping(source = CollectionAttribute.class, target = CollectionAttributeDTO.class)
    AttributeDTO toDTO(Attribute attribute);

    BasicAttributeDTO toDTO(BasicAttribute attribute);

    CompositeAttributeDTO toDTO(CompositeAttribute attribute);

    @Mapping(target = "targetRootName", source = "targetRoot.primaryTableName")
    SingularReferenceAttributeDTO toDTO(SingularReferenceAttribute attribute);

    @Mapping(target = "targetRootName", source = "targetRoot.primaryTableName")
    PluralReferenceAttributeDTO toDTO(PluralReferenceAttribute attribute);

    CollectionAttributeDTO toDTO(CollectionAttribute attribute);

    // CollectionElements
    @SubclassMapping(source = BasicElement.class, target = BasicElementDTO.class)
    @SubclassMapping(source = CompositeElement.class, target = CompositeElementDTO.class)
    CollectionElementDTO toDTO(CollectionElement element);

    BasicElementDTO toDTO(BasicElement element);

    CompositeElementDTO toDTO(CompositeElement element);

    // DataTypes
    @SubclassMapping(source = NumericType.class, target = NumericTypeDTO.class)
    @SubclassMapping(source = StringType.class, target = StringTypeDTO.class)
    @SubclassMapping(source = BooleanType.class, target = BooleanTypeDTO.class)
    @SubclassMapping(source = DateType.class, target = DateTypeDTO.class)
    @SubclassMapping(source = TimeType.class, target = TimeTypeDTO.class)
    @SubclassMapping(source = TimezoneType.class, target = TimezoneTypeDTO.class)
    @SubclassMapping(source = DateTimeType.class, target = DateTimeTypeDTO.class)
    @SubclassMapping(source = DayOfWeekType.class, target = DayOfWeekTypeDTO.class)
    @SubclassMapping(source = EnumType.class, target = EnumTypeDTO.class)
    @SubclassMapping(source = ListType.class, target = ListTypeDTO.class)
    DataTypeDTO toDTO(DataType dataType);

    NumericTypeDTO toDTO(NumericType dataType);

    StringTypeDTO toDTO(StringType dataType);

    BooleanTypeDTO toDTO(BooleanType dataType);

    DateTypeDTO toDTO(DateType dataType);

    TimeTypeDTO toDTO(TimeType dataType);

    TimezoneTypeDTO toDTO(TimezoneType dataType);

    DateTimeTypeDTO toDTO(DateTimeType dataType);

    DayOfWeekTypeDTO toDTO(DayOfWeekType dataType);

    EnumTypeDTO toDTO(EnumType dataType);

    ListTypeDTO toDTO(ListType dataType);

    // Collections
    List<AttributeDTO> toAttributeDTOList(List<Attribute> attributes);

    Set<AttributeDTO> toAttributeDTOSet(Set<Attribute> attributes);

    Set<RootDTO> toRootDTOSet(Set<Root> roots);
}
