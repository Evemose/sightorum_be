package com.rorm.dataimport.pipeline;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public record ImportProgress(
    long totalRows,
    long rowsProcessed,
    long rowsFailed,
    List<ImportEvent> events
) {

    public static ImportProgress initial(long totalRows) {
        return new ImportProgress(totalRows, 0, 0, List.of());
    }

    public ImportProgress append(ImportEvent event) {
        var newEvents = new ArrayList<>(events);
        newEvents.add(event);
        return switch (event) {
            case ImportEvent.ChunkProcessed cp -> new ImportProgress(
                totalRows, rowsProcessed + cp.rowsWritten(), rowsFailed,
                Collections.unmodifiableList(newEvents)
            );
            case ImportEvent.ChunkFailed _ -> new ImportProgress(
                totalRows, rowsProcessed, rowsFailed + 1,
                Collections.unmodifiableList(newEvents)
            );
        };
    }

    public double progressPercent() {
        if (totalRows <= 0) {
            return 0.0;
        }
        return (double) rowsProcessed / totalRows * 100.0;
    }
}
