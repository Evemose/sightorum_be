package com.rorm.client.import_.dto;

import java.util.List;

public record UploadResponse(
    String uploadId,
    List<FileInfo> files
) {
    public record FileInfo(
        String originalName,
        long size
    ) {}
}
