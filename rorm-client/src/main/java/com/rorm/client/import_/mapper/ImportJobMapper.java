package com.rorm.client.import_.mapper;

import com.rorm.client.import_.ImportJob;
import com.rorm.client.import_.dto.ImportJobResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

@Mapper(componentModel = "spring")
public interface ImportJobMapper {

    @Mapping(target = "status", expression = "java(job.getStatus().name())")
    @Mapping(source = "targetSchema", target = "targetSchema")
    @Mapping(source = "totalRows", target = "totalRows")
    @Mapping(source = "processedRows", target = "processedRows")
    @Mapping(source = "errorMessage", target = "errorMessage")
    @Mapping(source = "startedAt", target = "startedAt")
    @Mapping(source = "completedAt", target = "completedAt")
    ImportJobResponse toResponse(ImportJob job);

    List<ImportJobResponse> toResponses(List<ImportJob> jobs);
}
