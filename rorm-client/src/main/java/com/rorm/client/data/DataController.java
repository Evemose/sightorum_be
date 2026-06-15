package com.rorm.client.data;

import com.rorm.client.data.dto.DatasetInfo;
import com.rorm.client.data.dto.SampleResponse;
import com.rorm.client.data.dto.TableProfileDTO;
import com.rorm.client.utils.WithSchema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/datasets")
@RequiredArgsConstructor
@Validated
public class DataController {

    private final DatasetService datasetService;

    @GetMapping
    public ResponseEntity<List<DatasetInfo>> listDatasets() {
        return ResponseEntity.ok(datasetService.listDatasets());
    }

    @WithSchema("schema")
    @GetMapping("/{schema}")
    public ResponseEntity<DatasetInfo> getDataset(
        @PathVariable String schema
    ) {
        return ResponseEntity.ok(datasetService.getDatasetInfo(schema));
    }

    @WithSchema("schema")
    @GetMapping("/{schema}/samples")
    public ResponseEntity<SampleResponse> getSamples(
        @PathVariable String schema,
        @RequestParam(defaultValue = "100") @Min(1) @Max(value = 1000, message = "{validation.query.limit.max}") int limit,
        @RequestParam String table
    ) {
        return ResponseEntity.ok(datasetService.getSamples(schema, table, limit));
    }

    /**
     * Pre-computed per-attribute profile for a single table. Built during
     * import by {@code SchemaAnalyzer} and stored in {@code schema_profiles}.
     * 404 means no profile is on file (older imports, analyzer failure,
     * or the table doesn't exist in the schema).
     */
    @WithSchema("schema")
    @GetMapping("/{schema}/tables/{table}/profile")
    public ResponseEntity<TableProfileDTO> getTableProfile(
        @PathVariable String schema,
        @PathVariable String table
    ) {
        return datasetService.getTableProfile(schema, table)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
