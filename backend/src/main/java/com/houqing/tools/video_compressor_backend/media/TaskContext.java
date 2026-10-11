package com.houqing.tools.video_compressor_backend.media;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.util.List;

/** Everything a handler needs to do its work and report progress, with cancellation and the owner baked in. */
public interface TaskContext {
    String taskId();
    long ownerId();
    JsonNode params();
    List<MediaFile> inputs();
    Path inputPath(MediaFile file);
    Path workDir();
    CancellationToken token();

    /** Report fractional progress (0-100) with a human stage label; throttled and clamped by the engine. */
    void progress(int percent, String stage);

    MediaProbe.MediaInfo probe(MediaFile file);
}
