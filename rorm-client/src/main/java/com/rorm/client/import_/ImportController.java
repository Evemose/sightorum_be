package com.rorm.client.import_;

import com.rorm.client.import_.dto.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/import")
@RequiredArgsConstructor
public class ImportController {

    private final ImportJobService importJobService;

    @PostMapping(value = "/upload-multiple", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<UploadResponse> uploadFiles(
        @RequestParam("files") List<MultipartFile> files
    ) throws IOException {
        var response = importJobService.uploadFiles(files);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/detect")
    public ResponseEntity<DetectedSchemaResponse> detectSchema(
        @Valid @RequestBody DetectSchemaRequest request
    ) throws IOException {
        var response = importJobService.detectSchema(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/jobs")
    public ResponseEntity<ImportJobResponse> startImport(
        @Valid @RequestBody StartImportRequest request
    ) {
        var response = importJobService.startImport(request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    @GetMapping("/jobs/{id}")
    public ResponseEntity<ImportJobResponse> getJob(@PathVariable UUID id) {
        var response = importJobService.getJob(id);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/jobs")
    public ResponseEntity<List<ImportJobResponse>> listJobs() {
        var response = importJobService.listJobs();
        return ResponseEntity.ok(response);
    }
}
