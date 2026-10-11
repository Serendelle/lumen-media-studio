package com.houqing.tools.video_compressor_backend.media;

import com.fasterxml.jackson.databind.JsonNode;
import com.houqing.tools.video_compressor_backend.support.ApiTestBase;
import com.houqing.tools.video_compressor_backend.support.TestMedia;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;

/**
 * Every processing path on real media through the HTTP API, then inspects the produced files with FFprobe/ExifTool.
 * No processing is mocked; outputs are verified independently of the handler's own summary.
 */
class MediaPipelineTests extends ApiTestBase {

    @Autowired TaskEngine engine;
    @Autowired TaskRepository tasks;
    @Autowired MediaProbe probe;
    @Autowired StorageService storage;
    @Autowired ExifToolRunner exif;

    private String uploadVideo(User u) throws Exception {
        return json(upload("/api/video/upload", TestMedia.get("demo.mp4"), "演示 视频.mp4", u)).path("uniqueFilename").asText();
    }

    private MediaProbe.MediaInfo probeOutput(User u, JsonNode task) {
        return probe.probe(storage.pathOf(u.id(), task.path("downloadFilename").asText()));
    }

    @Test
    void videoUploadReportsServerSideProbe() throws Exception {
        TestMedia.assumeTools(Tool.FFMPEG, Tool.FFPROBE);
        User u = register();
        JsonNode r = json(upload("/api/video/upload", TestMedia.get("demo.mp4"), "demo.mp4", u));
        JsonNode info = r.path("videoInfo");
        assertThat(info.path("width").asInt()).isEqualTo(1280);
        assertThat(info.path("height").asInt()).isEqualTo(720);
        assertThat(info.path("duration").asDouble()).isBetween(5.5, 6.5);
        assertThat(info.path("hasAudio").asBoolean()).isTrue();
        assertThat(r.path("uniqueFilename").asText()).matches("[0-9a-f-]{36}");
    }

    @Test
    void crfCompressionWithDownscaleProducesValidSmallerVideo() throws Exception {
        TestMedia.assumeTools(Tool.FFMPEG, Tool.FFPROBE);
        User u = register();
        String id = uploadVideo(u);
        JsonNode t = json(postJson("/api/video/compress", Map.of("uniqueFilename", id, "mode", "crf", "crfValue", 30,
                "resolution", "480p", "fps", 24), u));
        JsonNode done = waitTask(t.path("taskId").asText(), u, 120_000);
        assertThat(done.path("status").asText()).isEqualTo("SUCCEEDED");
        MediaProbe.MediaInfo out = probeOutput(u, done);
        assertThat(out.height()).isEqualTo(480);
        assertThat(out.width() % 2).isZero();
        assertThat(out.fps()).isBetween(23.9, 24.1);
        assertThat(out.videoCodec()).isEqualTo("h264");
        assertThat(out.hasAudio()).isTrue();
        assertThat(done.path("result").path("outputSize").asLong()).isLessThan(done.path("result").path("originalSize").asLong());
        assertThat(done.path("output").path("filename").asText()).isEqualTo("演示 视频_compressed.mp4");
    }

    @Test
    void legacyWidthHeightNeverUpscale() throws Exception {
        TestMedia.assumeTools(Tool.FFMPEG, Tool.FFPROBE);
        User u = register();
        String id = uploadVideo(u);
        // original clients sent the source width/height; that must be a no-op, not a resize
        JsonNode t = json(postJson("/api/video/compress", Map.of("uniqueFilename", id, "mode", "crf", "crfValue", 35,
                "width", 1920, "height", 1080), u));
        JsonNode done = waitTask(t.path("taskId").asText(), u, 120_000);
        assertThat(probeOutput(u, done).height()).isEqualTo(720);
    }

    @Test
    void targetSizeIsMetWithTwoPassForBothCodecs() throws Exception {
        TestMedia.assumeTools(Tool.FFMPEG, Tool.FFPROBE);
        User u = register();
        String id = uploadVideo(u);
        for (String codec : List.of("h264", "h265")) {
            JsonNode t = json(postJson("/api/video/compress", Map.of("uniqueFilename", id, "mode", "bitrate",
                    "targetSizeMB", 1.5, "codec", codec, "audioBitrateKbps", 96), u));
            JsonNode done = waitTask(t.path("taskId").asText(), u, 180_000);
            assertThat(done.path("status").asText()).as(codec).isEqualTo("SUCCEEDED");
            long size = Files.size(storage.pathOf(u.id(), done.path("downloadFilename").asText()));
            assertThat(size).as(codec + " size").isLessThanOrEqualTo((long) (1.5 * 1024 * 1024));
            assertThat(size).as(codec + " not absurdly small").isGreaterThan(500_000);
            assertThat(probeOutput(u, done).videoCodec()).isEqualTo(codec.equals("h265") ? "hevc" : "h264");
        }
    }

    @Test
    void unreachableTargetIsRefusedWithSuggestionNotFaked() throws Exception {
        TestMedia.assumeTools(Tool.FFMPEG, Tool.FFPROBE);
        User u = register();
        String id = uploadVideo(u);
        JsonNode est = json(postJson("/api/video/compress/estimate", Map.of("uniqueFilename", id, "mode", "size", "targetSizeMB", 0.05), u));
        assertThat(est.path("feasible").asBoolean()).isFalse();
        assertThat(est.path("suggestions").size()).isPositive();

        JsonNode t = json(postJson("/api/video/compress", Map.of("uniqueFilename", id, "mode", "size", "targetSizeMB", 0.05), u));
        JsonNode done = waitTask(t.path("taskId").asText(), u, 60_000);
        assertThat(done.path("status").asText()).isEqualTo("FAILED");
        assertThat(done.path("errorCode").asText()).isEqualTo("TARGET_UNREACHABLE");
        assertThat(done.has("downloadFilename")).isFalse();
    }

    @Test
    void audioExtractionAllFormatsAndRepeatable() throws Exception {
        TestMedia.assumeTools(Tool.FFMPEG, Tool.FFPROBE);
        User u = register();
        String id = uploadVideo(u);
        Map<String, String> expectedCodec = Map.of("mp3", "mp3", "aac", "aac", "m4a", "aac", "wav", "pcm_s16le", "flac", "flac");
        for (var e : expectedCodec.entrySet()) {
            JsonNode t = json(postJson("/api/audio/extract", Map.of("uniqueFilename", id, "targetFormat", e.getKey(),
                    "sampleRate", 44100, "channels", 1), u));
            JsonNode done = waitTask(t.path("taskId").asText(), u, 60_000);
            assertThat(done.path("status").asText()).as(e.getKey()).isEqualTo("SUCCEEDED");
            MediaProbe.MediaInfo out = probeOutput(u, done);
            assertThat(out.hasVideo()).isFalse();
            assertThat(out.audioCodec()).as(e.getKey()).isEqualTo(e.getValue());
            assertThat(out.audioSampleRate()).isEqualTo(44100);
            assertThat(out.audioChannels()).isEqualTo(1);
        }
        // H-04 regression: running the same extraction twice used to hang forever
        for (int i = 0; i < 2; i++) {
            JsonNode t = json(postJson("/api/audio/extract", Map.of("uniqueFilename", id, "targetFormat", "mp3"), u));
            assertThat(waitTask(t.path("taskId").asText(), u, 60_000).path("status").asText()).isEqualTo("SUCCEEDED");
        }
    }

    @Test
    void convertAndWatermark() throws Exception {
        TestMedia.assumeTools(Tool.FFMPEG, Tool.FFPROBE);
        User u = register();
        String id = uploadVideo(u);
        JsonNode done = waitTask(json(postJson("/api/video/convert", Map.of("uniqueFilename", id, "targetFormat", "webm"), u))
                .path("taskId").asText(), u, 180_000);
        assertThat(done.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(probeOutput(u, done).videoCodec()).isEqualTo("vp9");

        String logo = json(upload("/api/image/upload", TestMedia.get("logo.png"), "logo.png", u)).path("uniqueFilename").asText();
        done = waitTask(json(postJson("/api/video/watermark", Map.of("videoUniqueFilename", id, "watermarkUniqueFilename", logo,
                "position", "center", "opacity", 0.4), u)).path("taskId").asText(), u, 120_000);
        assertThat(done.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(probeOutput(u, done).width()).isEqualTo(1280);
    }

    @Test
    void imageCompressionToTargetSizeAndBatchZip() throws Exception {
        TestMedia.assumeTools(Tool.FFMPEG, Tool.FFPROBE, Tool.MAGICK, Tool.EXIFTOOL);
        User u = register();
        String photo = json(upload("/api/image/upload", TestMedia.get("photo.jpg"), "photo.jpg", u)).path("uniqueFilename").asText();
        String logo = json(upload("/api/image/upload", TestMedia.get("logo.png"), "logo.png", u)).path("uniqueFilename").asText();
        for (String fmt : List.of("jpg", "webp", "avif")) {
            JsonNode done = waitTask(json(postJson("/api/image/process", Map.of("filenames", List.of(photo), "tool", "compress",
                    "settings", Map.of("format", fmt, "targetSizeKB", 60)), u)).path("taskId").asText(), u, 120_000);
            assertThat(done.path("status").asText()).as(fmt).isEqualTo("SUCCEEDED");
            JsonNode item = done.path("result").path("items").get(0);
            assertThat(item.path("targetMet").asBoolean()).as(fmt).isTrue();
            long size = Files.size(storage.pathOf(u.id(), done.path("downloadFilename").asText()));
            assertThat(size).as(fmt).isLessThanOrEqualTo(60 * 1024);
        }
        // unreachable target on a lossless format is reported honestly
        JsonNode png = waitTask(json(postJson("/api/image/process", Map.of("filenames", List.of(photo), "tool", "compress",
                "settings", Map.of("format", "png", "targetSizeKB", 1)), u)).path("taskId").asText(), u, 120_000);
        assertThat(png.path("result").path("items").get(0).path("targetMet").asBoolean()).isFalse();

        // resize + batch -> zip with per-file outputs
        JsonNode batch = waitTask(json(postJson("/api/image/process", Map.of("filenames", List.of(photo, logo), "tool", "resize",
                "settings", Map.of("width", 400)), u)).path("taskId").asText(), u, 120_000);
        assertThat(batch.path("output").path("mediaType").asText()).isEqualTo("ARCHIVE");
        String perFile = batch.path("result").path("items").get(0).path("outputFileId").asText();
        assertThat(probe.probe(storage.pathOf(u.id(), perFile)).width()).isEqualTo(400);
    }

    @Test
    void metadataEditReadBackAndVerifiedScrub() throws Exception {
        TestMedia.assumeTools(Tool.FFMPEG, Tool.FFPROBE, Tool.MAGICK, Tool.EXIFTOOL);
        User u = register();
        String photo = json(upload("/api/metadata/upload", TestMedia.get("photo.jpg"), "photo.jpg", u)).path("uniqueFilename").asText();
        JsonNode view = json(postJson("/api/metadata/view", Map.of("uniqueFilename", photo), u));
        assertThat(view.path("gps").path("latitude").asDouble()).isCloseTo(31.2304, org.assertj.core.data.Offset.offset(1e-3));
        assertThat(view.path("hasPrivacyData").asBoolean()).isTrue();

        MvcResult mod = postJson("/api/metadata/modify", Map.of("uniqueFilename", photo, "title", "西湖日落", "artist", "小李",
                "copyright", "© 2026 小李", "dateTimeOriginal", "2024:04:06 18:30:00"), u);
        assertThat(mod.getResponse().getStatus()).isEqualTo(200);
        Path edited = storage.pathOf(u.id(), json(mod).path("downloadFilename").asText());
        JsonNode tags = exif.readJson(edited, DATA_DIR.resolve("tmp"), null);
        String flat = tags.toString();
        assertThat(flat).contains("西湖日落").contains("小李").contains("2024:04:06 18:30:00");

        MvcResult scrub = postJson("/api/metadata/remove", Map.of("uniqueFilename", photo, "mode", "location"), u);
        assertThat(json(scrub).path("result").path("verified").asBoolean()).isTrue();
        JsonNode after = exif.readJson(storage.pathOf(u.id(), json(scrub).path("downloadFilename").asText()), DATA_DIR.resolve("tmp"), null);
        assertThat(ExifToolRunner.locationTags(after)).isEmpty();
        assertThat(after.toString()).contains("TestCam"); // location-only mode keeps the rest

        MvcResult all = postJson("/api/metadata/remove", Map.of("uniqueFilename", photo), u);
        JsonNode allTags = exif.readJson(storage.pathOf(u.id(), json(all).path("downloadFilename").asText()), DATA_DIR.resolve("tmp"), null);
        assertThat(allTags.toString()).doesNotContain("TestCam");
    }

    @Test
    void videoMetadataWriteAndUnsupportedFieldsAreRefused() throws Exception {
        TestMedia.assumeTools(Tool.FFMPEG, Tool.FFPROBE, Tool.EXIFTOOL);
        User u = register();
        String video = uploadVideo(u);
        MvcResult mod = postJson("/api/metadata/modify", Map.of("uniqueFilename", video, "title", "旅行 Vlog",
                "gpsLatitude", 30.25, "gpsLongitude", 120.17), u);
        assertThat(mod.getResponse().getStatus()).isEqualTo(200);
        JsonNode tags = exif.readJson(storage.pathOf(u.id(), json(mod).path("downloadFilename").asText()), DATA_DIR.resolve("tmp"), null);
        assertThat(tags.toString()).contains("旅行 Vlog");
        assertThat(ExifToolRunner.locationTags(tags)).isNotEmpty();

        String mp3 = json(upload("/api/metadata/upload", TestMedia.get("song.mp3"), "song.mp3", u)).path("uniqueFilename").asText();
        MvcResult refused = postJson("/api/metadata/modify", Map.of("uniqueFilename", mp3, "gpsLatitude", 1, "gpsLongitude", 2), u);
        assertThat(refused.getResponse().getStatus()).isEqualTo(422);
        assertThat(json(refused).path("details").path("unsupportedFields").toString()).contains("gps");
        MvcResult okTitle = postJson("/api/metadata/modify", Map.of("uniqueFilename", mp3, "title", "夜曲"), u);
        assertThat(okTitle.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void pdfRoundTrip() throws Exception {
        TestMedia.assumeTools(Tool.FFMPEG, Tool.FFPROBE, Tool.MAGICK, Tool.GHOSTSCRIPT, Tool.EXIFTOOL);
        User u = register();
        String photo = json(upload("/api/image/upload", TestMedia.get("photo.jpg"), "photo.jpg", u)).path("uniqueFilename").asText();
        String logo = json(upload("/api/image/upload", TestMedia.get("logo.png"), "logo.png", u)).path("uniqueFilename").asText();
        JsonNode merged = waitTask(json(postJson("/api/pdf/process", Map.of("filenames", List.of(photo, logo),
                "tool", "images-to-pdf", "settings", Map.of()), u)).path("taskId").asText(), u, 120_000);
        assertThat(merged.path("status").asText()).isEqualTo("SUCCEEDED");
        JsonNode pages = waitTask(json(postJson("/api/pdf/process", Map.of("filenames", List.of(merged.path("downloadFilename").asText()),
                "tool", "pdf-to-image", "settings", Map.of("density", 72)), u)).path("taskId").asText(), u, 120_000);
        assertThat(pages.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(pages.path("result").path("pages").asInt()).isEqualTo(2);
    }

    @Test
    void cancelAndRetryRunningTask() throws Exception {
        TestMedia.assumeTools(Tool.FFMPEG, Tool.FFPROBE);
        User u = register();
        String id = uploadVideo(u);
        String taskId = json(postJson("/api/video/compress", Map.of("uniqueFilename", id, "mode", "crf", "crfValue", 16,
                "preset", "slow", "codec", "h265"), u)).path("taskId").asText();
        long end = System.currentTimeMillis() + 20_000;
        while (!json(get("/api/tasks/" + taskId, u)).path("status").asText().equals("RUNNING") && System.currentTimeMillis() < end) Thread.sleep(100);
        assertThat(json(postJson("/api/tasks/" + taskId + "/cancel", Map.of(), u)).path("taskId").asText()).isEqualTo(taskId);
        JsonNode cancelled = waitTask(taskId, u, 30_000);
        assertThat(cancelled.path("status").asText()).isEqualTo("CANCELLED");
        assertThat(Files.exists(DATA_DIR.resolve("work").resolve(taskId))).isFalse(); // work dir cleaned
        assertThat(postJson("/api/tasks/" + taskId + "/cancel", Map.of(), u).getResponse().getStatus()).isEqualTo(409);

        JsonNode retried = json(postJson("/api/tasks/" + taskId + "/retry", Map.of(), u));
        assertThat(retried.path("attempt").asInt()).isEqualTo(2);
        postJson("/api/tasks/" + taskId + "/cancel", Map.of(), u);
        assertThat(waitTask(taskId, u, 30_000).path("status").asText()).isEqualTo("CANCELLED");
    }

    @Test
    void missingInputFileFailsCleanlyAndCanBeRetried() throws Exception {
        TestMedia.assumeTools(Tool.FFMPEG, Tool.FFPROBE);
        User u = register();
        String id = uploadVideo(u);
        Path stored = storage.pathOf(u.id(), id);
        Path backup = stored.resolveSibling("backup.bin");
        Files.move(stored, backup); // simulate the file vanishing from disk
        JsonNode done = waitTask(json(postJson("/api/audio/extract", Map.of("uniqueFilename", id, "targetFormat", "mp3"), u))
                .path("taskId").asText(), u, 60_000);
        assertThat(done.path("status").asText()).isEqualTo("FAILED");
        assertThat(done.path("message").asText()).doesNotContain(DATA_DIR.toString());

        Files.move(backup, stored);
        JsonNode retried = json(postJson("/api/tasks/" + done.path("taskId").asText() + "/retry", Map.of(), u));
        assertThat(waitTask(retried.path("taskId").asText(), u, 60_000).path("status").asText()).isEqualTo("SUCCEEDED");
    }

    @Test
    void tasksLeftRunningByACrashAreRecoveredAsRetryableFailures() throws Exception {
        User u = register();
        TaskEntity t = new TaskEntity();
        t.setId(java.util.UUID.randomUUID().toString());
        t.setOwnerId(u.id());
        t.setType("audio.extract");
        t.setStatus("RUNNING");
        t.setParamsJson("{}");
        t.setInputFiles("[]");
        t.setCreatedAt(System.currentTimeMillis());
        t.setUpdatedAt(System.currentTimeMillis());
        tasks.save(t);

        engine.recoverInterrupted(); // what happens on the next startup

        TaskEntity after = tasks.findById(t.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo("FAILED");
        assertThat(after.getErrorCode()).isEqualTo("INTERRUPTED");
        assertThat(after.getFinishedAt()).isNotNull();
    }

    @Test
    void sseStreamSendsSnapshotFirst() throws Exception {
        TestMedia.assumeTools(Tool.FFMPEG, Tool.FFPROBE);
        User u = register();
        String id = uploadVideo(u);
        String taskId = json(postJson("/api/audio/extract", Map.of("uniqueFilename", id, "targetFormat", "wav"), u)).path("taskId").asText();
        waitTask(taskId, u, 60_000);
        MvcResult async = mvc.perform(auth(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/tasks/" + taskId + "/events"), u)).andReturn();
        MvcResult r = mvc.perform(asyncDispatch(async)).andReturn();
        String body = r.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(body).contains("event:status").contains("\"status\":\"SUCCEEDED\"");
        User other = register();
        assertThat(get("/api/tasks/" + taskId + "/events", other).getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void expiredFilesArePurged() throws Exception {
        TestMedia.assumeTools(Tool.FFMPEG, Tool.FFPROBE, Tool.EXIFTOOL);
        User u = register();
        String id = json(upload("/api/image/upload", TestMedia.get("logo.png"), "logo.png", u)).path("uniqueFilename").asText();
        Path p = storage.pathOf(u.id(), id);
        MediaFile mf = storage.require(u.id(), id);
        mf.setExpiresAt(System.currentTimeMillis() - 1000);
        org.springframework.test.util.ReflectionTestUtils.getField(storage, "repo");
        ((MediaFileRepository) org.springframework.test.util.ReflectionTestUtils.getField(storage, "repo")).save(mf);
        storage.purgeExpired();
        assertThat(Files.exists(p)).isFalse();
        assertThat(get("/api/files/" + id, u).getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void backgroundRemovalWithRembg() throws Exception {
        // first run downloads the u2net model (~170 MB); allow a generous timeout
        TestMedia.assumeTools(Tool.FFMPEG, Tool.FFPROBE, Tool.EXIFTOOL, Tool.REMBG);
        User u = register();
        String photo = json(upload("/api/image/upload", TestMedia.get("photo.jpg"), "photo.jpg", u)).path("uniqueFilename").asText();
        JsonNode done = waitTask(json(postJson("/api/image/remove-background", Map.of("uniqueFilename", photo), u))
                .path("taskId").asText(), u, 900_000);
        assertThat(done.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(done.path("output").path("filename").asText()).endsWith("_nobg.png");
        assertThat(probe.probe(storage.pathOf(u.id(), done.path("downloadFilename").asText())).width()).isEqualTo(1600);
    }
}
