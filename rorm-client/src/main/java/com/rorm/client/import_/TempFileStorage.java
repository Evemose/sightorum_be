package com.rorm.client.import_;

import com.rorm.client.config.RormClientProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

@Slf4j
@Component
@RequiredArgsConstructor
public class TempFileStorage {

    private final RormClientProperties properties;
    private Path tempDir;

    @PostConstruct
    public void init() throws IOException {
        tempDir = Path.of(properties.tempFileDir());
        Files.createDirectories(tempDir);
        log.info("Temp file storage initialized at: {}", tempDir);
    }

    /**
     * Stores multiple files in a UUID-named subdirectory, preserving original filenames.
     * This enables multi-file dataset imports while maintaining file identity.
     */
    public StoredUpload storeFiles(List<MultipartFile> files) throws IOException {
        var uploadId = UUID.randomUUID().toString();
        var uploadDir = tempDir.resolve(uploadId);
        Files.createDirectories(uploadDir);

        var storedFiles = new ArrayList<StoredFile>();
        for (var file : files) {
            var originalName = file.getOriginalFilename();
            if (originalName == null || originalName.isBlank()) {
                originalName = "unnamed_file";
            }

            var targetPath = uploadDir.resolve(originalName);
            file.transferTo(targetPath);

            storedFiles.add(new StoredFile(originalName, file.getSize()));
            log.info("Stored file: {} in upload: {}", originalName, uploadId);
        }

        return new StoredUpload(uploadId, storedFiles);
    }

    /**
     * Lists all files in an upload directory.
     */
    public List<Path> listFiles(String uploadId) throws IOException {
        var uploadDir = getUploadDir(uploadId);
        try (Stream<Path> files = Files.list(uploadDir)) {
            return files.filter(Files::isRegularFile).toList();
        }
    }

    /**
     * Gets the upload directory path for a given uploadId.
     */
    public Path getUploadDir(String uploadId) {
        var uploadDir = tempDir.resolve(uploadId);
        if (!Files.exists(uploadDir) || !Files.isDirectory(uploadDir)) {
            throw new IllegalArgumentException("Upload directory not found: " + uploadId);
        }
        return uploadDir;
    }

    /**
     * Gets a specific file path from an upload directory.
     */
    public Path getFilePath(String uploadId, String fileName) {
        var uploadDir = getUploadDir(uploadId);
        var filePath = uploadDir.resolve(fileName);
        if (!Files.exists(filePath)) {
            throw new IllegalArgumentException("File not found: " + fileName + " in upload: " + uploadId);
        }
        return filePath;
    }

    @Scheduled(fixedRateString = "PT1H")
    public void cleanupExpiredUploads() {
        try (Stream<Path> uploads = Files.list(tempDir)) {
            var cutoff = Instant.now().minus(properties.tempFileTtlHours(), ChronoUnit.HOURS);

            uploads.filter(Files::isDirectory).forEach(uploadDir -> {
                try {
                    var lastModified = Files.getLastModifiedTime(uploadDir).toInstant();
                    if (lastModified.isBefore(cutoff)) {
                        deleteUpload(uploadDir.getFileName().toString());
                        log.info("Cleaned up expired upload: {}", uploadDir.getFileName());
                    }
                } catch (IOException e) {
                    log.warn("Failed to check/delete upload directory: {}", uploadDir, e);
                }
            });
        } catch (IOException e) {
            log.error("Error during temp file cleanup", e);
        }
    }

    /**
     * Deletes an entire upload directory.
     */
    public void deleteUpload(String uploadId) {
        var uploadDir = tempDir.resolve(uploadId);
        if (Files.exists(uploadDir)) {
            try (var files = Files.walk(uploadDir)) {
                files
                    .sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) {
                            log.warn("Failed to delete: {}", path, e);
                        }
                    });
            } catch (IOException e) {
                log.warn("Failed to delete upload directory: {}", uploadDir, e);
            }
            log.debug("Deleted upload directory: {}", uploadId);
        }
    }

    public record StoredUpload(
        String uploadId,
        List<StoredFile> files
    ) {}

    public record StoredFile(
        String originalName,
        long size
    ) {}
}
