package com.houqing.tools.video_compressor_backend.media;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/** One media operation. Implementations are pure processing logic; the engine owns lifecycle and persistence. */
public interface MediaTaskHandler {
    /** Stable task type key, e.g. "video.compress". */
    String type();

    /** Validate params + input roles before the task is queued; throw ApiException on bad input. */
    void validate(JsonNode params, List<MediaFile> inputs);

    /** Do the work. Must honour ctx.token() cancellation (ProcessRunner does this automatically). */
    HandlerResult execute(TaskContext ctx) throws Exception;

    /** Optional: which external tools this handler needs, so the engine can fail fast with a clear message. */
    default List<Tool> requiredTools() { return List.of(); }

    record HandlerResult(MediaFile output, com.fasterxml.jackson.databind.node.ObjectNode summary) {}
}
