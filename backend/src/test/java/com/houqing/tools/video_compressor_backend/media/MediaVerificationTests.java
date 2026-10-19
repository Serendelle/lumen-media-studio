package com.houqing.tools.video_compressor_backend.media;

import com.fasterxml.jackson.databind.JsonNode;
import com.houqing.tools.video_compressor_backend.common.ApiException;
import com.houqing.tools.video_compressor_backend.common.ErrorCode;
import com.houqing.tools.video_compressor_backend.support.ApiTestBase;
import com.houqing.tools.video_compressor_backend.support.TestMedia;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V2 P0 item 2: outputs are proven from the file itself, never from an exit code or the handler's own numbers. */
class MediaVerificationTests extends ApiTestBase {

    @Autowired MediaVerifier verifier;
    @Autowired MediaProbe probe;
    @Autowired StorageService storage;

    private String uploadVideo(User u) throws Exception {
        return json(upload("/api/files/upload", TestMedia.get("demo.mp4"), "demo.mp4", u)).path("fileId").asText();
    }

    private static JsonNode check(JsonNode verification, String name) {
        for (JsonNode c : verification.path("checks")) if (c.path("name").asText().equals(name)) return c;
        throw new AssertionError("no check named " + name + " in " + verification);
    }

    // ---------------- target size proof ----------------

    @Test
    void sizeTargetIsProvenFromRealBytesAndFramesDecode() throws Exception {
        TestMedia.assumeTools(Tool.FFMPEG, Tool.FFPROBE);
        User u = register();
        String id = uploadVideo(u);
        JsonNode done = waitTask(json(postJson("/api/video/compress", Map.of("uniqueFilename", id, "mode", "size",
                "targetSizeMB", 2, "audioBitrateKbps", 96), u)).path("taskId").asText(), u, 180_000);
        assertThat(done.path("status").asText()).isEqualTo("SUCCEEDED");
        JsonNode v = done.path("result").path("verification");
        assertThat(v.path("status").asText()).isEqualTo("VERIFIED");
        long actual = Files.size(storage.pathOf(u.id(), done.path("downloadFilename").asText()));
        assertThat(v.path("measured").path("actualBytes").asLong()).isEqualTo(actual);
        assertThat(v.path("measured").path("targetBytes").asLong()).isEqualTo(2 * MediaVerifier.MIB);
        assertThat(actual).isLessThanOrEqualTo(2 * MediaVerifier.MIB);
        assertThat(done.path("result").path("targetMet").asBoolean()).isTrue();
        assertThat(check(v, "目标体积").path("ok").asBoolean()).isTrue();
        assertThat(check(v, "画面可解码").path("ok").asBoolean()).isTrue();
        assertThat(check(v, "音轨").path("ok").asBoolean()).isTrue();
    }

    @Test
    void anOversizedFileIsTargetNotMetEvenThoughItIsAValidVideo() throws Exception {
        TestMedia.assumeTools(Tool.FFMPEG, Tool.FFPROBE);
        Path demo = TestMedia.get("demo.mp4"); // ~4.6 MiB, perfectly valid
        MediaProbe.MediaInfo info = probe.probe(demo);
        var params = mapper.readTree("{\"mode\":\"size\",\"targetSizeMB\":1}");
        MediaVerifier.Verification v = verifier.verifyCompress(demo, params, info, null);
        assertThat(v.passed()).isFalse();
        assertThat(v.toJson().path("status").asText()).isEqualTo("TARGET_NOT_MET");
        assertThat(check(v.toJson(), "目标体积").path("ok").asBoolean()).isFalse();
        assertThat(check(v.toJson(), "画面可解码").path("ok").asBoolean()).isTrue(); // only the size is wrong
        assertThatThrownBy(v::throwIfFailed).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.getCode()).isEqualTo(ErrorCode.TARGET_NOT_MET);
            assertThat(e.getMessage()).contains("目标体积");
            assertThat(e.getDetails()).containsKey("verification");
        });
    }

    @Test
    void framesThatDoNotDecodeFailEvenWhenTheHeaderProbes() throws Exception {
        TestMedia.assumeTools(Tool.FFMPEG, Tool.FFPROBE);
        Path dir = TestMedia.DIR;
        Path fast = dir.resolve("faststart.mp4");
        if (!Files.exists(fast)) {
            run(TestMedia.tools().command(Tool.FFMPEG).get(0), "-v", "error", "-y", "-i", TestMedia.get("demo.mp4").toString(),
                    "-c", "copy", "-movflags", "+faststart", fast.toString());
        }
        byte[] all = Files.readAllBytes(fast);
        Path cut = dir.resolve("cut-tail.mp4");
        Files.write(cut, Arrays.copyOf(all, (int) (all.length * 0.55))); // moov intact up front, tail frames gone
        MediaProbe.MediaInfo mi = probe.probe(cut);
        assertThat(mi.hasVideo()).isTrue();
        assertThat(verifier.decodes(fast, mi.duration(), null)).isTrue();
        assertThat(verifier.decodes(cut, mi.duration(), null)).isFalse();
    }

    // ---------------- convert: real container check (was always true) ----------------

    @Test
    void convertCheckDistinguishesContainersInsteadOfAlwaysPassing() throws Exception {
        TestMedia.assumeTools(Tool.FFMPEG, Tool.FFPROBE);
        Path demo = TestMedia.get("demo.mp4");
        MediaProbe.MediaInfo info = probe.probe(demo);
        assertThat(verifier.verifyConvert(demo, "mp4", info, null).passed()).isTrue();
        for (String wrong : List.of("mov", "webm", "mkv", "gif")) {
            MediaVerifier.Verification v = verifier.verifyConvert(demo, wrong, info, null);
            assertThat(v.passed()).as("mp4 must not pass as " + wrong).isFalse();
            assertThat(check(v.toJson(), "封装格式").path("ok").asBoolean()).as(wrong).isFalse();
        }
    }

    @Test
    void realConversionsAreVerifiedIncludingGifWithoutAudioRule() throws Exception {
        TestMedia.assumeTools(Tool.FFMPEG, Tool.FFPROBE);
        User u = register();
        String id = uploadVideo(u);
        for (String target : List.of("mov", "mkv", "gif")) {
            JsonNode done = waitTask(json(postJson("/api/video/convert", Map.of("uniqueFilename", id, "targetFormat", target), u))
                    .path("taskId").asText(), u, 180_000);
            assertThat(done.path("status").asText()).as(target).isEqualTo("SUCCEEDED");
            JsonNode v = done.path("result").path("verification");
            assertThat(v.path("status").asText()).as(target).isEqualTo("VERIFIED");
            JsonNode audio = check(v, "音轨");
            if (target.equals("gif")) assertThat(audio.path("expected").asText()).contains("GIF");
            else assertThat(audio.path("expected").asText()).contains("保留");
        }
    }

    // ---------------- audio extraction ----------------

    @Test
    void silentVideoIsRefusedWithAudioStreamMissingAndNoTaskIsCreated() throws Exception {
        TestMedia.assumeTools(Tool.FFMPEG, Tool.FFPROBE);
        Path silent = TestMedia.DIR.resolve("silent.mp4");
        if (!Files.exists(silent)) {
            run(TestMedia.tools().command(Tool.FFMPEG).get(0), "-v", "error", "-y", "-f", "lavfi", "-i",
                    "testsrc2=size=320x240:rate=15", "-t", "2", "-c:v", "libx264", "-an", silent.toString());
        }
        User u = register();
        String id = json(upload("/api/files/upload", silent, "silent.mp4", u)).path("fileId").asText();
        var r = postJson("/api/audio/extract", Map.of("uniqueFilename", id, "targetFormat", "mp3"), u);
        assertThat(r.getResponse().getStatus()).isEqualTo(422);
        assertThat(json(r).path("code").asText()).isEqualTo("AUDIO_STREAM_MISSING");
        assertThat(json(get("/api/tasks", u)).path("totalElements").asInt()).isZero();
        assertThat(json(get("/api/files?role=OUTPUT", u)).path("totalElements").asInt()).isZero();
    }

    @Test
    void extractedAudioHasOneAudioStreamNoVideoAndSaysWhetherItTranscoded() throws Exception {
        TestMedia.assumeTools(Tool.FFMPEG, Tool.FFPROBE);
        User u = register();
        String id = uploadVideo(u);
        JsonNode mp3 = waitTask(json(postJson("/api/audio/extract", Map.of("uniqueFilename", id, "targetFormat", "mp3",
                "sampleRate", 44100, "channels", 1), u)).path("taskId").asText(), u, 60_000);
        JsonNode v = mp3.path("result").path("verification");
        assertThat(v.path("status").asText()).isEqualTo("VERIFIED");
        assertThat(v.path("measured").path("videoStreams").asInt()).isZero();
        assertThat(v.path("measured").path("audioStreams").asInt()).isEqualTo(1);
        assertThat(check(v, "采样率").path("ok").asBoolean()).isTrue();
        assertThat(mp3.path("result").path("operation").asText()).isEqualTo("transcode");
        assertThat(mp3.path("result").path("sourceAudioCodec").asText()).isEqualTo("aac");

        JsonNode flac = waitTask(json(postJson("/api/audio/extract", Map.of("uniqueFilename", id, "targetFormat", "flac"), u))
                .path("taskId").asText(), u, 60_000);
        assertThat(flac.path("result").path("operation").asText()).isEqualTo("extract-lossless");

        // extraction may also run on a processed output (e.g. the compressed video), exactly the file chosen
        JsonNode compressed = waitTask(json(postJson("/api/video/compress", Map.of("uniqueFilename", id, "mode", "crf",
                "crf", 30, "resolution", "360p"), u)).path("taskId").asText(), u, 120_000);
        String outId = compressed.path("downloadFilename").asText();
        JsonNode fromOut = waitTask(json(postJson("/api/audio/extract", Map.of("uniqueFilename", outId, "targetFormat", "mp3"), u))
                .path("taskId").asText(), u, 60_000);
        assertThat(fromOut.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(fromOut.path("inputs").get(0).path("fileId").asText()).isEqualTo(outId);
    }

    // ---------------- image batches ----------------

    @Test
    void oneBrokenImageMakesTheBatchPartialNotFailed() throws Exception {
        TestMedia.assumeTools(Tool.FFMPEG, Tool.FFPROBE, Tool.MAGICK);
        User u = register();
        String photo = json(upload("/api/image/upload", TestMedia.get("photo.jpg"), "photo.jpg", u)).path("uniqueFilename").asText();
        byte[] png = Files.readAllBytes(TestMedia.get("logo.png"));
        var badUpload = uploadBytes("/api/image/upload", Arrays.copyOf(png, 40), "broken.png", u); // valid signature, no image data
        assertThat(badUpload.getResponse().getStatus()).isEqualTo(200);
        String bad = json(badUpload).path("uniqueFilename").asText();

        JsonNode mixed = waitTask(json(postJson("/api/image/process", Map.of("filenames", List.of(photo, bad), "tool", "resize",
                "settings", Map.of("width", 300)), u)).path("taskId").asText(), u, 120_000);
        assertThat(mixed.path("status").asText()).isEqualTo("SUCCEEDED");
        JsonNode r = mixed.path("result");
        assertThat(r.path("overall").asText()).isEqualTo("PARTIAL_SUCCESS");
        assertThat(r.path("succeeded").asInt()).isEqualTo(1);
        assertThat(r.path("failed").asInt()).isEqualTo(1);
        JsonNode failedItem = r.path("items").get(1);
        assertThat(failedItem.path("ok").asBoolean()).isFalse();
        assertThat(failedItem.has("outputFileId")).isFalse();
        assertThat(failedItem.path("errorCode").asText()).isNotBlank();
        // the deliverable contains only the successful file
        assertThat(mixed.path("output").path("mediaType").asText()).isEqualTo("IMAGE");

        JsonNode allBad = waitTask(json(postJson("/api/image/process", Map.of("filenames", List.of(bad), "tool", "resize",
                "settings", Map.of("width", 300)), u)).path("taskId").asText(), u, 120_000);
        assertThat(allBad.path("status").asText()).isEqualTo("FAILED");
        assertThat(allBad.path("result").path("items").get(0).path("ok").asBoolean()).isFalse();
    }

    // ---------------- compare API ----------------

    @Test
    void compareMeasuresBothFilesAndIsOwnerOnly() throws Exception {
        TestMedia.assumeTools(Tool.FFMPEG, Tool.FFPROBE);
        User u = register(), other = register();
        String id = uploadVideo(u);
        JsonNode done = waitTask(json(postJson("/api/video/compress", Map.of("uniqueFilename", id, "mode", "crf", "crf", 32,
                "resolution", "480p"), u)).path("taskId").asText(), u, 120_000);
        String out = done.path("downloadFilename").asText();

        JsonNode c = json(get("/api/v2/media/compare?sourceFileId=" + id + "&outputFileId=" + out, u));
        assertThat(c.path("source").path("sizeBytes").asLong()).isEqualTo(Files.size(TestMedia.get("demo.mp4")));
        assertThat(c.path("output").path("sizeBytes").asLong()).isEqualTo(Files.size(storage.pathOf(u.id(), out)));
        assertThat(c.path("output").path("measured").path("height").asInt()).isEqualTo(480);
        assertThat(c.path("source").path("measured").path("height").asInt()).isEqualTo(720);
        assertThat(c.path("savedPercent").asDouble()).isPositive();
        assertThat(c.path("deltaBytes").asLong()).isNegative();
        assertThat(Math.abs(c.path("durationDeltaMs").asLong())).isLessThan(250);

        assertThat(get("/api/v2/media/compare?sourceFileId=" + id + "&outputFileId=" + out, other).getResponse().getStatus()).isEqualTo(404);
        assertThat(get("/api/v2/media/compare?sourceFileId=" + id + "&outputFileId=" + out, null).getResponse().getStatus()).isEqualTo(401);
    }

    private static void run(String... cmd) throws Exception {
        Process p = new ProcessBuilder(new ArrayList<>(Arrays.asList(cmd))).redirectErrorStream(true).start();
        p.getOutputStream().close();
        p.getInputStream().readAllBytes();
        assertThat(p.waitFor()).isZero();
    }
}
