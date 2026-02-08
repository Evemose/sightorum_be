package com.rorm.dataimport.pipeline;

import com.rorm.metamodel.ModelSpace;
import reactor.core.publisher.Flux;

public record ImportResult(
    String targetSchema,
    ModelSpace modelSpace,
    Flux<ImportProgress> progress
) {
    public long totalRowsImported() {
        var last = progress.blockLast();
        return last != null ? last.rowsProcessed() : 0L;
    }
}
