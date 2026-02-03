package com.rorm.client.import_.mapper;

import com.rorm.client.import_.dto.SchemaOverrideDTO;
import com.rorm.dataimport.override.SchemaOverride;
import org.mapstruct.InjectionStrategy;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.SubclassMapping;

import java.util.List;
import java.util.Map;

@Mapper(
    componentModel = "spring",
    uses = {DataTypeMapper.class},
    injectionStrategy = InjectionStrategy.CONSTRUCTOR
)
public interface SchemaOverrideMapper {

    @SubclassMapping(source = SchemaOverrideDTO.Basic.class, target = SchemaOverride.BasicAttributeOverride.class)
    @SubclassMapping(source = SchemaOverrideDTO.Collection.class, target = SchemaOverride.CollectionAttributeOverride.class)
    @SubclassMapping(source = SchemaOverrideDTO.Id.class, target = SchemaOverride.IdAttributeOverride.class)
    @SubclassMapping(source = SchemaOverrideDTO.Composite.class, target = SchemaOverride.CompositeAttributeOverride.class)
    @SubclassMapping(source = SchemaOverrideDTO.OneToOneRoot.class, target = SchemaOverride.OneToOneRootOverride.class)
    @SubclassMapping(source = SchemaOverrideDTO.SingularReference.class, target = SchemaOverride.SingularReferenceOverride.class)
    @SubclassMapping(source = SchemaOverrideDTO.PluralReference.class, target = SchemaOverride.PluralReferenceOverride.class)
    SchemaOverride toSchemaOverride(SchemaOverrideDTO dto);

    List<SchemaOverride> toSchemaOverrides(List<SchemaOverrideDTO> dtos);

    Map<String, List<SchemaOverride>> toSchemaOverridesMap(Map<String, List<SchemaOverrideDTO>> dtosMap);

    // Specific mappings for each subtype
    @Mapping(target = "dataType", source = "dataType")
    SchemaOverride.BasicAttributeOverride toBasicOverride(SchemaOverrideDTO.Basic dto);

    @Mapping(target = "elementType", source = "elementType")
    SchemaOverride.CollectionAttributeOverride toCollectionOverride(SchemaOverrideDTO.Collection dto);

    @Mapping(target = "dataType", source = "dataType")
    SchemaOverride.IdAttributeOverride toIdOverride(SchemaOverrideDTO.Id dto);

    @Mapping(target = "nestedOverrides", source = "nestedOverrides")
    SchemaOverride.CompositeAttributeOverride toCompositeOverride(SchemaOverrideDTO.Composite dto);

    @Mapping(target = "nestedOverrides", source = "nestedOverrides")
    SchemaOverride.OneToOneRootOverride toOneToOneRootOverride(SchemaOverrideDTO.OneToOneRoot dto);

    SchemaOverride.SingularReferenceOverride toSingularReferenceOverride(SchemaOverrideDTO.SingularReference dto);

    SchemaOverride.PluralReferenceOverride toPluralReferenceOverride(SchemaOverrideDTO.PluralReference dto);
}
