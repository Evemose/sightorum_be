package com.rorm.client.import_;

import java.time.Instant;
import java.util.List;

public record ImportEventLog(
    String type,
    long chunkNumber,
    Long rowsWritten,
    String errorMessage,
    List<String> warnings,
    Instant timestamp
) {

    public static ImportEventLog chunkProcessed(long chunkNumber, long rowsWritten, List<String> warnings, Instant timestamp) {
        return new ImportEventLog("chunk_processed", chunkNumber, rowsWritten, null, warnings, timestamp);
    }

    public static ImportEventLog chunkFailed(long chunkNumber, String errorMessage, Instant timestamp) {
        return new ImportEventLog("chunk_failed", chunkNumber, null, errorMessage, List.of(), timestamp);
    }
}
