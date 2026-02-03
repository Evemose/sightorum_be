package com.rorm.client.metamodel.dto;

import com.rorm.dto.MetamodelDTO.ModelSpaceDTO;

import java.time.Instant;
import java.util.UUID;

public record ModelSpaceResponse(
    UUID id,
    String schemaName,
    ModelSpaceDTO modelSpace,
    Instant createdAt,
    Instant updatedAt
) {}
