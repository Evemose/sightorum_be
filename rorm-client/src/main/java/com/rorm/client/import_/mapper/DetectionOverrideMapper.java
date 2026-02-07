package com.rorm.client.import_.mapper;

import com.rorm.client.import_.dto.DetectionOverrideDTO;
import com.rorm.client.import_.dto.HierarchicalOverrideDTO;
import com.rorm.client.import_.dto.SchemaOverrideDTO;
import com.rorm.dataimport.hierarchical.HierarchicalOverride;
import com.rorm.dataimport.override.DetectionOverride;
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
public interface DetectionOverrideMapper {

    // Top-level mapping that handles both schema and hierarchical overrides
    @SubclassMapping(source = SchemaOverrideDTO.Basic.class, target = SchemaOverride.BasicAttributeOverride.class)
    @SubclassMapping(source = SchemaOverrideDTO.Collection.class, target = SchemaOverride.CollectionAttributeOverride.class)
    @SubclassMapping(source = SchemaOverrideDTO.Id.class, target = SchemaOverride.IdAttributeOverride.class)
    @SubclassMapping(source = SchemaOverrideDTO.Composite.class, target = SchemaOverride.CompositeAttributeOverride.class)
    @SubclassMapping(source = SchemaOverrideDTO.OneToOneRoot.class, target = SchemaOverride.OneToOneRootOverride.class)
    @SubclassMapping(source = SchemaOverrideDTO.SingularReference.class, target = SchemaOverride.SingularReferenceOverride.class)
    @SubclassMapping(source = SchemaOverrideDTO.PluralReference.class, target = SchemaOverride.PluralReferenceOverride.class)
    @SubclassMapping(source = HierarchicalOverrideDTO.DataTypeOverride.class, target = HierarchicalOverride.DataTypeOverride.class)
    @SubclassMapping(source = HierarchicalOverrideDTO.ForceComposite.class, target = HierarchicalOverride.ForceComposite.class)
    @SubclassMapping(source = HierarchicalOverrideDTO.ForceSeparateRoot.class, target = HierarchicalOverride.ForceSeparateRoot.class)
    @SubclassMapping(source = HierarchicalOverrideDTO.ForceBasic.class, target = HierarchicalOverride.ForceBasic.class)
    @SubclassMapping(source = HierarchicalOverrideDTO.ForceReference.class, target = HierarchicalOverride.ForceReference.class)
    @SubclassMapping(source = HierarchicalOverrideDTO.IdOverride.class, target = HierarchicalOverride.IdOverride.class)
    DetectionOverride toDetectionOverride(DetectionOverrideDTO dto);

    List<DetectionOverride> toDetectionOverrides(List<DetectionOverrideDTO> dtos);

    Map<String, List<DetectionOverride>> toDetectionOverridesMap(Map<String, List<DetectionOverrideDTO>> dtosMap);

    // Schema override mappings
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

    // For nested overrides in Composite/OneToOneRoot (SchemaOverrideDTO only)
    @SubclassMapping(source = SchemaOverrideDTO.Basic.class, target = SchemaOverride.BasicAttributeOverride.class)
    @SubclassMapping(source = SchemaOverrideDTO.Collection.class, target = SchemaOverride.CollectionAttributeOverride.class)
    @SubclassMapping(source = SchemaOverrideDTO.Id.class, target = SchemaOverride.IdAttributeOverride.class)
    @SubclassMapping(source = SchemaOverrideDTO.Composite.class, target = SchemaOverride.CompositeAttributeOverride.class)
    @SubclassMapping(source = SchemaOverrideDTO.OneToOneRoot.class, target = SchemaOverride.OneToOneRootOverride.class)
    @SubclassMapping(source = SchemaOverrideDTO.SingularReference.class, target = SchemaOverride.SingularReferenceOverride.class)
    @SubclassMapping(source = SchemaOverrideDTO.PluralReference.class, target = SchemaOverride.PluralReferenceOverride.class)
    SchemaOverride toSchemaOverride(SchemaOverrideDTO dto);

    // Hierarchical override mappings
    @Mapping(target = "dataType", source = "dataType")
    HierarchicalOverride.DataTypeOverride toDataTypeOverride(HierarchicalOverrideDTO.DataTypeOverride dto);

    HierarchicalOverride.ForceComposite toForceComposite(HierarchicalOverrideDTO.ForceComposite dto);

    @Mapping(target = "idStrategy", source = "idStrategy")
    HierarchicalOverride.ForceSeparateRoot toForceSeparateRoot(HierarchicalOverrideDTO.ForceSeparateRoot dto);

    @Mapping(target = "dataType", source = "dataType")
    HierarchicalOverride.ForceBasic toForceBasic(HierarchicalOverrideDTO.ForceBasic dto);

    HierarchicalOverride.ForceReference toForceReference(HierarchicalOverrideDTO.ForceReference dto);

    @Mapping(target = "dataType", source = "dataType")
    HierarchicalOverride.IdOverride toIdOverride(HierarchicalOverrideDTO.IdOverride dto);

    // IdStrategy mappings
    @SubclassMapping(source = HierarchicalOverrideDTO.IdStrategyDTO.UseField.class, target = HierarchicalOverride.IdStrategy.UseField.class)
    @SubclassMapping(source = HierarchicalOverrideDTO.IdStrategyDTO.AutoGenerate.class, target = HierarchicalOverride.IdStrategy.AutoGenerate.class)
    HierarchicalOverride.IdStrategy toIdStrategy(HierarchicalOverrideDTO.IdStrategyDTO dto);

    @Mapping(target = "dataType", source = "dataType")
    HierarchicalOverride.IdStrategy.UseField toUseFieldIdStrategy(HierarchicalOverrideDTO.IdStrategyDTO.UseField dto);

    default HierarchicalOverride.IdStrategy.AutoGenerate toAutoGenerateIdStrategy(HierarchicalOverrideDTO.IdStrategyDTO.AutoGenerate dto) {
        return HierarchicalOverride.IdStrategy.AutoGenerate.INSTANCE;
    }
}
