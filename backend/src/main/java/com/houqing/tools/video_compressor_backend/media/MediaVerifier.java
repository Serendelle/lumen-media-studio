package com.houqing.tools.video_compressor_backend.media;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.houqing.tools.video_compressor_backend.common.ApiException;
import com.houqing.tools.video_compressor_backend.common.ErrorCode;
import com.houqing.tools.video_compressor_backend.media.handler.FfmpegCommands;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Independent evidence about a produced file: it never trusts the encoder's exit code or a handler's own numbers.
 * It measures the bytes on disk, re-probes the streams and decodes short segments at both ends. Handlers attach
 * the resulting {@link Verification} to their summary and fail the task when it does not pass; the AI quality
 * reviewer re-runs the same checks on its own.
 */
@Component
public class MediaVerifier {
    public static final long MIB = 1_048_576L;

    /** convert target -> expected container token in ffprobe's format_name, video codec, audio codec (null = none). */
    static final Map<String, String[]> CONVERT_EXPECT = Map.of(
            "mp4", new String[]{"mp4", "h264", "aac"},
            "mov", new String[]{"mov", "h264", "aac"},
            "webm", new String[]{"webm", "vp9", "opus"},
            "mkv", new String[]{"matroska", "h264", "aac"},
            "gif", new String[]{"gif", "gif", null});

    /** audio.extract format -> codecs ffprobe may report for it. */
    static final Map<String, List<String>> AUDIO_CODECS = Map.of(
            "mp3", List.of("mp3"),
            "wav", List.of("pcm_s16le"),
            "flac", List.of("flac"),
            "aac", List.of("aac"),
            "m4a", List.of("aac"));

    private final MediaProbe probe;
    private final ProcessRunner runner;
    private final ToolLocator tools;
    private final ObjectMapper mapper;

    public MediaVerifier(MediaProbe probe, ProcessRunner runner, ToolLocator tools, ObjectMapper mapper) {
        this.probe = probe;
        this.runner = runner;
        this.tools = tools;
        this.mapper = mapper;
    }

    // ---------------- evidence container ----------------

    public static final class Verification {
        private final ObjectMapper mapper;
        private final ArrayNode checks;
        private final ObjectNode measured;
        private String failureCode;

        public Verification(ObjectMapper mapper) {
            this.mapper = mapper;
            this.checks = mapper.createArrayNode();
            this.measured = mapper.createObjectNode();
        }

        public boolean check(String name, boolean ok, String expected, String actual, ErrorCode onFail) {
            ObjectNode c = checks.addObject();
            c.put("name", name);
            c.put("ok", ok);
            c.put("expected", expected);
            c.put("actual", actual);
            if (!ok && failureCode == null) failureCode = (onFail == null ? ErrorCode.OUTPUT_INVALID : onFail).name();
            return ok;
        }

        public ObjectNode measured() { return measured; }

        public boolean passed() { return failureCode == null; }

        public ArrayNode checks() { return checks; }

        /** {status: VERIFIED | <error code>, checks[], measured{}} */
        public ObjectNode toJson() {
            ObjectNode n = mapper.createObjectNode();
            n.put("status", passed() ? "VERIFIED" : failureCode);
            n.set("checks", checks);
            n.set("measured", measured);
            return n;
        }

        /** Fails the task with the first failed check's code and a message naming every failed check. */
        public void throwIfFailed() {
            if (passed()) return;
            List<String> failed = new ArrayList<>();
            for (JsonNode c : checks) {
                if (!c.path("ok").asBoolean()) failed.add(c.path("name").asText() + "：期望 " + c.path("expected").asText()
                        + "，实际 " + c.path("actual").asText());
            }
            ErrorCode code = ErrorCode.valueOf(failureCode);
            throw new ApiException(code, code.defaultMessage() + "（" + String.join("；", failed) + "）",
                    Map.of("verification", toJson()));
        }
    }

    public Verification start() { return new Verification(mapper); }

    // ---------------- measurements ----------------

    public record Streams(int video, int audio) {}

    /** Stream counts from the raw probe JSON (cover-art pictures are not counted as video). */
    public Streams streams(MediaProbe.MediaInfo mi) {
        int v = 0, a = 0;
        try {
            for (JsonNode s : mapper.readTree(mi.rawJson()).path("streams")) {
                String type = s.path("codec_type").asText();
                if ("video".equals(type) && s.path("disposition").path("attached_pic").asInt(0) != 1) v++;
                else if ("audio".equals(type)) a++;
            }
        } catch (Exception e) {
            return new Streams(mi.hasVideo() ? 1 : 0, mi.hasAudio() ? 1 : 0);
        }
        return new Streams(v, a);
    }

    /** ISO-BMFF major brand ("qt  " for QuickTime .mov, "isom"/"mp42"… for .mp4), or "" when absent. */
    public String majorBrand(MediaProbe.MediaInfo mi) {
        try {
            return mapper.readTree(mi.rawJson()).path("format").path("tags").path("major_brand").asText("");
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Decodes ~2 s at the start and ~2 s before the end. "FFprobe can read the header" is not proof that the
     * frames decode, so a truncated or corrupt stream fails here.
     */
    public boolean decodes(Path file, double durationSec, CancellationToken token) {
        if (!decodeSegment(file, 0, token)) return false;
        // the two 2 s windows only coincide for very short clips; anything longer gets its tail checked too
        return durationSec <= 2.5 || decodeSegment(file, Math.max(0, durationSec - 2.5), token);
    }

    /**
     * A segment passes only if FFmpeg reports no error AND actually produced output time: a seek past the end of a
     * truncated file decodes nothing and still exits 0, so "exit code 0" alone is not evidence.
     */
    private boolean decodeSegment(Path file, double startSec, CancellationToken token) {
        List<String> cmd = new ArrayList<>(tools.command(Tool.FFMPEG));
        cmd.addAll(List.of("-hide_banner", "-nostdin", "-nostats", "-v", "error", "-xerror"));
        if (startSec > 0) {
            cmd.add("-ss");
            cmd.add(String.format(Locale.ROOT, "%.2f", startSec));
        }
        cmd.add("-t");
        cmd.add("2");
        FfmpegCommands.input(cmd, file);
        cmd.addAll(List.of("-f", "null", "-progress", "pipe:1", "-"));
        StringBuilder errors = new StringBuilder();
        long[] decodedUs = {0};
        ProcessRunner.Result r = runner.run(cmd, Duration.ofSeconds(60), token, line -> {
            if (PROGRESS_LINE.matcher(line).matches()) {
                if (line.startsWith("out_time_us=")) {
                    try {
                        decodedUs[0] = Math.max(decodedUs[0], Long.parseLong(line.substring(12).trim()));
                    } catch (NumberFormatException ignored) {
                        // "N/A" before the first frame
                    }
                }
            } else if (!line.isBlank() && errors.length() < 400) {
                errors.append(line).append('\n');
            }
        });
        return r.ok() && errors.isEmpty() && decodedUs[0] > 0;
    }

    private static final java.util.regex.Pattern PROGRESS_LINE = java.util.regex.Pattern.compile("^[a-z_0-9]+=.*$");

    /** Fills the common measured facts for an audio/video output. */
    public MediaProbe.MediaInfo measure(Verification v, Path file) {
        long bytes = size(file);
        v.measured().put("actualBytes", bytes);
        MediaProbe.MediaInfo mi;
        try {
            mi = probe.probe(file);
        } catch (ApiException e) {
            v.check("可读取", false, "FFprobe 可解析", "无法解析", ErrorCode.OUTPUT_INVALID);
            return null;
        }
        Streams s = streams(mi);
        ObjectNode m = v.measured();
        m.put("formatName", mi.formatName());
        m.put("duration", Math.round(mi.duration() * 1000) / 1000.0);
        m.put("videoStreams", s.video());
        m.put("audioStreams", s.audio());
        if (mi.overallBitrate() > 0) m.put("bitrate", mi.overallBitrate());
        if (s.video() > 0) {
            m.put("videoCodec", mi.videoCodec());
            m.put("width", mi.width());
            m.put("height", mi.height());
            if (mi.fps() > 0) m.put("fps", Math.round(mi.fps() * 100) / 100.0);
            if (mi.videoBitrate() > 0) m.put("videoBitrate", mi.videoBitrate());
        }
        if (s.audio() > 0) {
            m.put("audioCodec", mi.audioCodec());
            m.put("audioSampleRate", mi.audioSampleRate());
            m.put("audioChannels", mi.audioChannels());
            if (mi.audioBitrate() > 0) m.put("audioBitrate", mi.audioBitrate());
        }
        return mi;
    }

    // ---------------- per-operation rule sets ----------------

    /** video.compress: real bytes vs target (MiB), codec, height cap, audio kept/dropped as asked, frames decode. */
    public Verification verifyCompress(Path out, JsonNode p, MediaProbe.MediaInfo source, CancellationToken token) {
        Verification v = start();
        MediaProbe.MediaInfo mi = measure(v, out);
        long bytes = v.measured().path("actualBytes").asLong();
        if ("size".equals(p.path("mode").asText())) {
            long target = targetBytes(p);
            v.measured().put("targetBytes", target);
            v.check("目标体积", bytes <= target, "≤ " + mib(target) + " MiB（" + target + " 字节）",
                    mib(bytes) + " MiB（" + bytes + " 字节）", ErrorCode.TARGET_NOT_MET);
        }
        if (mi == null) return v;
        String wantCodec = "h265".equals(p.path("codec").asText("h264")) ? "hevc" : "h264";
        v.check("视频编码", wantCodec.equals(mi.videoCodec()), wantCodec, String.valueOf(mi.videoCodec()), null);
        String res = p.path("resolution").asText("original");
        if (res.endsWith("p") && res.length() > 1) {
            int h = Integer.parseInt(res.substring(0, res.length() - 1));
            v.check("分辨率", mi.height() <= h, "高度 ≤ " + h, mi.width() + "×" + mi.height(), null);
        }
        Streams s = streams(mi);
        boolean wantAudio = source.hasAudio() && p.path("audioBitrateKbps").asInt(128) != 0;
        v.check("音轨", wantAudio ? s.audio() >= 1 : s.audio() == 0, wantAudio ? "保留音轨" : "无音轨",
                s.audio() + " 条音轨", null);
        boolean decodes = decodes(out, mi.duration(), token);
        v.check("画面可解码", decodes, "首尾片段可解码", decodes ? "通过" : "解码出错", null);
        return v;
    }

    /** video.convert: container really is the target, codecs match, audio follows the source, frames decode. */
    public Verification verifyConvert(Path out, String target, MediaProbe.MediaInfo source, CancellationToken token) {
        Verification v = start();
        String[] want = CONVERT_EXPECT.get(target);
        MediaProbe.MediaInfo mi = measure(v, out);
        if (mi == null || want == null) return v;
        String brand = majorBrand(mi).trim();
        boolean containerOk = List.of(mi.formatName().split(",")).contains(want[0]);
        // mp4 and mov share ffprobe's "mov,mp4,…" demuxer name; the major brand tells them apart
        if (target.equals("mov")) containerOk = containerOk && brand.equals("qt");
        if (target.equals("mp4")) containerOk = containerOk && !brand.equals("qt");
        v.check("封装格式", containerOk, target.toUpperCase(Locale.ROOT),
                mi.formatName() + (brand.isEmpty() ? "" : "（brand " + brand + "）"), null);
        Streams s = streams(mi);
        v.check("视频流", s.video() >= 1 && want[1].equals(mi.videoCodec()), want[1], s.video() + " 条 · " + mi.videoCodec(), null);
        if (want[2] == null) {
            // GIF has no audio by definition: require none instead of applying the MP4 audio rule
            v.check("音轨", s.audio() == 0, "无（GIF 不含音频）", s.audio() + " 条音轨", null);
        } else if (source.hasAudio()) {
            v.check("音轨", s.audio() >= 1 && want[2].equals(mi.audioCodec()), "保留 · " + want[2],
                    s.audio() + " 条 · " + (mi.audioCodec() == null ? "无" : mi.audioCodec()), null);
        } else {
            v.check("音轨", s.audio() == 0, "无（源文件无音轨）", s.audio() + " 条音轨", null);
        }
        boolean decodes = decodes(out, mi.duration(), token);
        v.check("画面可解码", decodes, "首尾片段可解码", decodes ? "通过" : "解码出错", null);
        return v;
    }

    /** audio.extract: ≥1 audio stream, 0 video streams, codec / sample rate / channels exactly as requested. */
    public Verification verifyAudio(Path out, String format, JsonNode p) {
        Verification v = start();
        MediaProbe.MediaInfo mi = measure(v, out);
        if (mi == null) return v;
        Streams s = streams(mi);
        v.check("音频流", s.audio() >= 1, "≥ 1 条", s.audio() + " 条", null);
        v.check("视频流", s.video() == 0, "0 条", s.video() + " 条", null);
        List<String> codecs = AUDIO_CODECS.getOrDefault(format, List.of(format));
        v.check("音频编码", codecs.contains(mi.audioCodec()), String.join(" / ", codecs), String.valueOf(mi.audioCodec()), null);
        int sr = p.path("sampleRate").asInt(0);
        if (sr > 0) v.check("采样率", mi.audioSampleRate() == sr, sr + " Hz", mi.audioSampleRate() + " Hz", null);
        int ch = p.path("channels").asInt(0);
        if (ch > 0) v.check("声道", mi.audioChannels() == ch, String.valueOf(ch), String.valueOf(mi.audioChannels()), null);
        v.check("非空", mi.duration() > 0, "时长 > 0", Math.round(mi.duration() * 10) / 10.0 + " 秒", null);
        return v;
    }

    // ---------------- helpers ----------------

    /** targetSizeMB is interpreted as MiB (1 MiB = 1,048,576 bytes), as the UI states. */
    public static long targetBytes(JsonNode p) {
        return (long) Math.floor(p.path("targetSizeMB").asDouble(0) * MIB);
    }

    public static String mib(long bytes) {
        return String.format(Locale.ROOT, "%.2f", bytes / (double) MIB);
    }

    private static long size(Path p) {
        try {
            return Files.size(p);
        } catch (Exception e) {
            return 0;
        }
    }
}
