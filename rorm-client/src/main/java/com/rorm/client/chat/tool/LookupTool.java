package com.rorm.client.chat.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rorm.client.data.DatasetService;
import com.rorm.client.import_.ImportJobService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class LookupTool {

    private final DatasetService datasetService;
    private final ImportJobService importJobService;
    private final ObjectMapper objectMapper;

    @Tool(
        name = "listDatasets",
        description = """
            List all datasets (schemas) available in the database. \
            Returns schema names, tables, row counts, and column names \
            for each dataset. Use this to discover what data is available \
            before querying or analyzing."""
    )
    public String listDatasets(ToolContext toolContext) {
        return toJson(datasetService.listDatasets());
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize lookup result", e);
            return "{\"error\":\"serialization failed\"}";
        }
    }

    @Tool(
        name = "getDatasetInfo",
        description = """
            Get detailed information about a specific dataset: its tables, \
            row counts, and column names. Use this to understand the \
            structure of a dataset before building queries."""
    )
    public String getDatasetInfo(
        @ToolParam(description = "The schema name of the dataset to inspect")
        String schemaName,
        ToolContext toolContext
    ) {
        return toJson(datasetService.getDatasetInfo(schemaName));
    }

    @Tool(
        name = "listImportJobs",
        description = """
            List all import jobs with their status (QUEUED, IN_PROGRESS, \
            COMPLETED, FAILED), target schema, row counts, and timestamps. \
            Use this to check what imports have been run or are running."""
    )
    public String listImportJobs(ToolContext toolContext) {
        return toJson(importJobService.listJobs());
    }
}
