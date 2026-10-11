package com.houqing.tools.video_compressor_backend.media;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

/** Client-facing task shape used by the unified task API and SSE events. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskView(
        String taskId,
        String type,
        String status,
        int progress,
        String stage,
        String message,
        String errorCode,
        /** Original contract field: file id to pass to GET /api/video/download/{filename}. */
        String downloadFilename,
        String origin,
        int attempt,
        OutputView output,
        JsonNode result,
        long createdAt,
        Long startedAt,
        Long finishedAt) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OutputView(String fileId, String filename, long sizeBytes, String mediaType, String downloadUrl) {}
}
