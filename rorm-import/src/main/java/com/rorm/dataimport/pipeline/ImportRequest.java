package com.rorm.dataimport.pipeline;

import com.rorm.dataimport.source.ImportDataSource;
import com.rorm.metamodel.ModelSpace;

import java.util.List;

public record ImportRequest(
    String targetSchema,
    List<ImportDataSource> dataSources,
    ModelSpace modelSpace,
    int chunkSize
) {
    public ImportRequest(String targetSchema, List<ImportDataSource> dataSources, ModelSpace modelSpace) {
        this(targetSchema, dataSources, modelSpace, 1000);
    }

    public ImportRequest {
        chunkSize = chunkSize > 0 ? chunkSize : 1000;
    }
}
