package com.rorm.client.import_.mapper;

import com.rorm.client.import_.dto.DetectedSchemaResponse;
import com.rorm.client.import_.dto.DetectedSchemaResponse.DetectedIdColumnDTO;
import com.rorm.client.import_.dto.DetectedSchemaResponse.DetectedRootDTO;
import com.rorm.dataimport.pipeline.DetectedSchema;
import com.rorm.dataimport.pipeline.SchemaDetector.DetectedIdColumn;
import com.rorm.dataimport.pipeline.SchemaDetector.DetectedRoot;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.Map;

@Mapper(
    componentModel = "spring",
    uses = {DetectedAttributeMapper.class, DataTypeMapper.class}
)
public interface DetectedSchemaMapper {

    @Mapping(target = "roots", source = "roots")
    DetectedSchemaResponse toResponse(DetectedSchema schema);

    @Mapping(target = "name", source = "name")
    @Mapping(target = "sourceDataSource", source = "sourceDataSource")
    @Mapping(target = "attributes", source = "attributes")
    @Mapping(target = "idColumn", source = "idColumn")
    DetectedRootDTO toRootDTO(DetectedRoot root);

    Map<String, DetectedRootDTO> toRootDTOMap(Map<String, DetectedRoot> roots);

    @Mapping(target = "attributeName", source = "attributeName")
    @Mapping(target = "columnName", source = "columnName")
    @Mapping(target = "dataType", source = "dataType")
    DetectedIdColumnDTO toIdColumnDTO(DetectedIdColumn idColumn);
}
