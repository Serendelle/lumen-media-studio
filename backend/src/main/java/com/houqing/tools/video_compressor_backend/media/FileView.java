package com.houqing.tools.video_compressor_backend.media;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

/** Client-facing description of a stored file. uniqueFilename is kept for the original contract and equals fileId. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FileView(
        String fileId,
        String uniqueFilename,
        String originalFilename,
        String role,
        String mediaType,
        String mimeType,
        long size,
        String sourceTaskId,
        long createdAt,
        long expiresAt,
        JsonNode info) {
}
