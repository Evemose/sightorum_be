package com.rorm.dataimport.pipeline;

import com.rorm.dataimport.source.ImportDataSource;

import java.util.List;

public record LoadedSources(
    ImportPipelineRequest request,
    List<ImportDataSource> dataSources
) {}
