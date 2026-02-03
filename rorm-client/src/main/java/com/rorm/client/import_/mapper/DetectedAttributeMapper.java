package com.rorm.client.import_.mapper;

import com.rorm.client.import_.dto.DetectedSchemaResponse.DetectedAttributeDTO;
import com.rorm.dataimport.attribute.DetectedAttribute;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.SubclassMapping;

import java.util.Map;

@Mapper(
    componentModel = "spring",
    uses = {DataTypeMapper.class}
)
public interface DetectedAttributeMapper {

    @SubclassMapping(source = DetectedAttribute.Basic.class, target = DetectedAttributeDTO.Basic.class)
    @SubclassMapping(source = DetectedAttribute.Collection.class, target = DetectedAttributeDTO.Collection.class)
    @SubclassMapping(source = DetectedAttribute.SingularReference.class, target = DetectedAttributeDTO.SingularReference.class)
    @SubclassMapping(source = DetectedAttribute.PluralReference.class, target = DetectedAttributeDTO.PluralReference.class)
    @SubclassMapping(source = DetectedAttribute.Composite.class, target = DetectedAttributeDTO.Composite.class)
    @SubclassMapping(source = DetectedAttribute.OneToOneRoot.class, target = DetectedAttributeDTO.OneToOne.class)
    DetectedAttributeDTO toDTO(DetectedAttribute attr);

    Map<String, DetectedAttributeDTO> toDTOMap(Map<String, DetectedAttribute> attributes);

    @Mapping(target = "name", source = "name")
    @Mapping(target = "sourceColumn", source = "source.sourceColumn")
    @Mapping(target = "dataType", source = "dataType")
    DetectedAttributeDTO.Basic toBasicDTO(DetectedAttribute.Basic basic);

    @Mapping(target = "name", source = "name")
    @Mapping(target = "sourceColumn", source = "source.sourceColumn")
    @Mapping(target = "elementType", source = "elementType")
    @Mapping(target = "separator", source = "separator")
    DetectedAttributeDTO.Collection toCollectionDTO(DetectedAttribute.Collection collection);

    @Mapping(target = "name", source = "name")
    @Mapping(target = "sourceColumn", source = "source.sourceColumn")
    @Mapping(target = "targetRootName", source = "targetRootName")
    @Mapping(target = "dataType", source = "dataType")
    DetectedAttributeDTO.SingularReference toSingularReferenceDTO(DetectedAttribute.SingularReference ref);

    @Mapping(target = "name", source = "name")
    @Mapping(target = "sourceColumn", source = "source.sourceColumn")
    @Mapping(target = "targetRootName", source = "targetRootName")
    @Mapping(target = "dataType", source = "dataType")
    DetectedAttributeDTO.PluralReference toPluralReferenceDTO(DetectedAttribute.PluralReference ref);

    @Mapping(target = "name", source = "name")
    @Mapping(target = "subAttributes", source = "subAttributes")
    DetectedAttributeDTO.Composite toCompositeDTO(DetectedAttribute.Composite composite);

    @Mapping(target = "name", source = "name")
    @Mapping(target = "targetRootName", source = "targetRootName")
    @Mapping(target = "subAttributes", source = "subAttributes")
    DetectedAttributeDTO.OneToOne toOneToOneDTO(DetectedAttribute.OneToOneRoot oneToOne);
}
