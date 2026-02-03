package com.rorm.client.data;

import com.rorm.client.data.dto.QueryRequest;
import com.rorm.client.data.dto.QueryResponse;
import com.rorm.client.validation.ValidSchemaName;
import com.rorm.dto.QueryDTO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/datasets/{schema}/query")
@RequiredArgsConstructor
@Validated
public class QueryController {

    private final DatasetService datasetService;

    @PostMapping
    public ResponseEntity<QueryResponse> executeQuery(
        @PathVariable @ValidSchemaName String schema,
        @Valid @RequestBody QueryRequest request
    ) {
        var response = datasetService.executeQuery(request.query());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/dto")
    public ResponseEntity<QueryResponse> executeQueryDTO(
        @PathVariable @ValidSchemaName String schema,
        @Valid @RequestBody QueryDTO queryDTO
    ) {
        var response = datasetService.executeQuery(schema, queryDTO);
        return ResponseEntity.ok(response);
    }
}
