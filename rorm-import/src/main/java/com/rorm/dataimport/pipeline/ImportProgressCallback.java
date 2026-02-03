package com.rorm.dataimport.pipeline;

import java.util.UUID;

/**
 * Callback interface for tracking import progress.
 * Implementations can use this to publish progress events via SSE or other mechanisms.
 */
public interface ImportProgressCallback {

    /**
     * A no-op callback for cases where progress tracking is not needed.
     */
    ImportProgressCallback NOOP = new ImportProgressCallback() {
        @Override
        public void onChunkCompleted(UUID jobId, String rootName, long processed, long total) {
        }

        @Override
        public void onStepCompleted(UUID jobId, String rootName, long totalRows) {
        }

        @Override
        public void onJobCompleted(UUID jobId, ImportResult result) {
        }

        @Override
        public void onJobFailed(UUID jobId, Exception error) {
        }
    };

    /**
     * Called when a chunk of rows has been processed.
     *
     * @param jobId     The import job ID
     * @param rootName  The root entity being imported
     * @param processed Number of rows processed so far
     * @param total     Total number of rows (may be estimated)
     */
    void onChunkCompleted(UUID jobId, String rootName, long processed, long total);

    /**
     * Called when a step (single root import) has completed.
     *
     * @param jobId     The import job ID
     * @param rootName  The root entity that was imported
     * @param totalRows Total rows imported for this root
     */
    void onStepCompleted(UUID jobId, String rootName, long totalRows);

    /**
     * Called when the entire import job has completed successfully.
     *
     * @param jobId  The import job ID
     * @param result The import result
     */
    void onJobCompleted(UUID jobId, ImportResult result);

    /**
     * Called when the import job has failed.
     *
     * @param jobId The import job ID
     * @param error The error that caused the failure
     */
    void onJobFailed(UUID jobId, Exception error);
}
