package com.rorm.client.chat.tool;

import com.rorm.client.import_.ImportJobService;
import com.rorm.client.import_.dto.StartImportRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class ImportTool {

    private final ImportJobService importJobService;

    @Tool(
        name = "startImport",
        description = """
            Start a data import job from previously uploaded files. \
            Requires an uploadId from a prior file upload. \
            The import runs asynchronously — returns a job ID the user can \
            track via the import event stream. Use this when the user wants \
            to import/load data files into a schema."""
    )
    public String startImport(
        @ToolParam(description = "Upload ID from a prior file upload")
        String uploadId,
        @ToolParam(description = "Target schema name to import data into")
        String targetSchema,
        @ToolParam(description = "Number of rows per processing chunk (100-100000, default 1000)")
        int chunkSize,
        ToolContext toolContext
    ) {
        var request = new StartImportRequest(
            uploadId, targetSchema, chunkSize, Map.of(), List.of());
        var response = importJobService.startImport(request);
        return "Import started. Job ID: " + response.id() +
               ". Target schema: " + targetSchema +
               ". Track progress at /import/jobs/" + response.id() + "/stream";
    }

    @Tool(
        name = "getImportStatus",
        description = """
            Check the status of an import job. Returns current status \
            (QUEUED, IN_PROGRESS, COMPLETED, FAILED), row counts, and \
            any error messages."""
    )
    public String getImportStatus(
        @ToolParam(description = "The import job ID to check")
        String jobId,
        ToolContext toolContext
    ) {
        var response = importJobService.getJob(UUID.fromString(jobId));
        return "Import job " + response.id() +
               ": status=" + response.status() +
               ", schema=" + response.targetSchema() +
               ", processed=" + response.processedRows() +
               "/" + response.totalRows() +
               (response.errorMessage() != null ? ", error=" + response.errorMessage() : "");
    }
}
