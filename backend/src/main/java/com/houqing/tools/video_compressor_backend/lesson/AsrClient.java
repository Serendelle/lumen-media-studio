package com.houqing.tools.video_compressor_backend.lesson;

import com.houqing.tools.video_compressor_backend.media.CancellationToken;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/** Speech recognition with sentence timestamps — the lesson timeline is built on them. */
public interface AsrClient {

    record Sentence(long beginMs, long endMs, String text) {}

    /** @param stage receives short human-readable stage names ("上传音频", "排队识别"...) */
    List<Sentence> transcribe(Path audio, CancellationToken token, Consumer<String> stage);

    boolean configured();

    String model();
}
