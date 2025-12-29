package com.rorm.dataimport.pipeline;

import com.rorm.metamodel.ModelSpace;

public record ImportResult(
    String targetSchema,
    ModelSpace modelSpace,
    long totalRowsImported
) {
}
