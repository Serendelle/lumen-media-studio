package com.houqing.tools.video_compressor_backend.media.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.houqing.tools.video_compressor_backend.common.ApiException;
import com.houqing.tools.video_compressor_backend.common.ErrorCode;
import com.houqing.tools.video_compressor_backend.config.AppProperties;
import com.houqing.tools.video_compressor_backend.media.*;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Extracts an audio track to mp3/wav/flac/aac/m4a, re-encoding correctly per format. */
@Component
public class AudioExtractHandler implements MediaTaskHandler {

    private static final Map<String, String[]> FORMATS = Map.of(
            "mp3", new String[]{"libmp3lame", "audio/mpeg"},
            "wav", new String[]{"pcm_s16le", "audio/wav"},
            "flac", new String[]{"flac", "audio/flac"},
            "aac", new String[]{"aac", "audio/aac"},
            "m4a", new String[]{"aac", "audio/mp4"});

    static final List<Integer> SAMPLE_RATES = List.of(8000, 16000, 22050, 32000, 44100, 48000, 96000);

    private final AppProperties props;
    private final ToolLocator tools;
    private final ProcessRunner runner;
    private final StorageService storage;
    private final ObjectMapper mapper;
    private final MediaVerifier verifier;

    public AudioExtractHandler(AppProperties props, ToolLocator tools, ProcessRunner runner,
                               StorageService storage, ObjectMapper mapper, MediaVerifier verifier) {
        this.props = props; this.tools = tools; this.runner = runner; this.storage = storage; this.mapper = mapper;
        this.verifier = verifier;
    }

    @Override public String type() { return "audio.extract"; }
    @Override public List<Tool> requiredTools() { return List.of(Tool.FFMPEG, Tool.FFPROBE); }

    @Override
    public void validate(JsonNode p, List<MediaFile> inputs) {
        if (inputs.size() != 1 || !inputs.get(0).kind().isAv())
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "需要一个含音频的视频或音频文件");
        // the upload already probed the streams: refuse before queueing instead of producing an empty file
        if (knownSilent(inputs.get(0))) throw new ApiException(ErrorCode.AUDIO_STREAM_MISSING);
        String fmt = p.path("format").asText("mp3");
        if (!FORMATS.containsKey(fmt)) throw new ApiException(ErrorCode.VALIDATION_FAILED, "不支持的音频格式: " + fmt);
        if (p.hasNonNull("bitrateKbps")) {
            int b = p.path("bitrateKbps").asInt(0);
            if (b < 32 || b > 320) throw new ApiException(ErrorCode.VALIDATION_FAILED, "音频码率需在 32-320 kbps 之间");
        }
        if (p.hasNonNull("sampleRate") && !SAMPLE_RATES.contains(p.path("sampleRate").asInt(0)))
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "采样率仅支持 " + SAMPLE_RATES);
        if (p.hasNonNull("channels") && !java.util.Set.of(1, 2).contains(p.path("channels").asInt(0)))
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "声道数仅支持 1 或 2");
    }

    @Override
    public HandlerResult execute(TaskContext ctx) {
        MediaFile input = ctx.inputs().get(0);
        MediaProbe.MediaInfo info = ctx.probe(input);
        if (!info.hasAudio()) throw new ApiException(ErrorCode.AUDIO_STREAM_MISSING);

        String fmt = ctx.params().path("format").asText("mp3");
        String[] spec = FORMATS.get(fmt);
        Path out = ctx.workDir().resolve("audio." + fmt);

        List<String> cmd = FfmpegCommands.start(tools, props.media().ffmpegThreads());
        FfmpegCommands.input(cmd, ctx.inputPath(input));
        cmd.add("-vn"); cmd.add("-map"); cmd.add("a:0");
        cmd.add("-c:a"); cmd.add(spec[0]);
        int bitrate = ctx.params().path("bitrateKbps").asInt(0);
        if (bitrate > 0 && (fmt.equals("mp3") || fmt.equals("aac") || fmt.equals("m4a"))) {
            cmd.add("-b:a"); cmd.add(bitrate + "k");
        }
        int sampleRate = ctx.params().path("sampleRate").asInt(0);
        if (sampleRate > 0) { cmd.add("-ar"); cmd.add(String.valueOf(sampleRate)); }
        int channels = ctx.params().path("channels").asInt(0);
        if (channels > 0) { cmd.add("-ac"); cmd.add(String.valueOf(channels)); }
        if (fmt.equals("aac")) { cmd.add("-f"); cmd.add("adts"); }
        cmd.add("-progress"); cmd.add("pipe:1");
        cmd.add(out.toString());

        runner.runChecked(cmd, Duration.ofMinutes(props.media().taskTimeoutMinutes()), ctx.token(),
                FfmpegProgress.lineConsumer(ctx, info.duration(), "extracting"));

        long size;
        try { size = Files.size(out); } catch (Exception e) { throw new ApiException(ErrorCode.PROCESS_FAILED); }
        ObjectNode summary = mapper.createObjectNode();
        summary.put("format", fmt);
        summary.put("outputSize", size);
        // MP3/AAC/M4A are always re-encoded; only WAV/FLAC are lossless with respect to the decoded source
        summary.put("operation", fmt.equals("wav") || fmt.equals("flac") ? "extract-lossless" : "transcode");
        summary.put("sourceAudioCodec", info.audioCodec());
        ctx.progress(99, "verifying");
        MediaVerifier.Verification v = verifier.verifyAudio(out, fmt, ctx.params());
        summary.set("verification", v.toJson());
        v.throwIfFailed();
        String name = StorageService.baseName(input.getOriginalName()) + "." + fmt;
        MediaFile mf = storage.registerOutput(ctx.ownerId(), out, name, MediaKind.AUDIO, spec[1], ctx.taskId());
        return new HandlerResult(mf, summary);
    }

    private boolean knownSilent(MediaFile f) {
        if (f.getProbeJson() == null) return false;
        try {
            JsonNode info = mapper.readTree(f.getProbeJson());
            return info.has("hasAudio") && !info.path("hasAudio").asBoolean();
        } catch (Exception e) {
            return false;
        }
    }
}
