package com.rorm.dataimport.pipeline.progress;

import com.rorm.dataimport.pipeline.ImportEvent;

import java.util.List;

public record ImportProgress(
    long totalRows,
    long rowsProcessed,
    long rowsFailed,
    List<ImportEvent> events
) {
}
