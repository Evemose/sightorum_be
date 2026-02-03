package com.rorm.client.import_.mapper;

import com.rorm.client.import_.dto.SimpleDataType;
import com.rorm.metamodel.DataType;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface DataTypeMapper {

    default DataType toDataType(SimpleDataType simpleDataType) {
        return simpleDataType != null ? simpleDataType.toMetamodel() : null;
    }

    default SimpleDataType toSimpleDataType(DataType dataType) {
        return dataType != null ? SimpleDataType.fromMetamodel(dataType) : null;
    }
}
