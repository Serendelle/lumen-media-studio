package com.houqing.tools.video_compressor_backend.lesson;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.houqing.tools.video_compressor_backend.auth.CurrentUser;
import com.houqing.tools.video_compressor_backend.auth.DownloadTicketService;
import com.houqing.tools.video_compressor_backend.common.ApiException;
import com.houqing.tools.video_compressor_backend.common.ErrorCode;
import com.houqing.tools.video_compressor_backend.config.AppProperties;
import com.houqing.tools.video_compressor_backend.media.TaskEngine;
import com.houqing.tools.video_compressor_backend.media.TaskEntity;
import jakarta.validation.constraints.NotBlank;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Learning player API (V2). Lessons belong to their owner; evidence frames are served through short-lived tickets. */
@RestController
@RequestMapping("/api/v2/lessons")
public class LessonController {
    static final Pattern FRAME = Pattern.compile("f\\d{5}\\.jpg");

    private final LessonRepository lessons;
    private final TaskEngine engine;
    private final AsrClient asr;
    private final OcrRunner ocr;
    private final LessonStructurer structurer;
    private final DownloadTicketService tickets;
    private final AppProperties props;
    private final ObjectMapper mapper;

    public LessonController(LessonRepository lessons, TaskEngine engine, AsrClient asr, OcrRunner ocr, LessonStructurer structurer,
                            DownloadTicketService tickets, AppProperties props, ObjectMapper mapper) {
        this.lessons = lessons;
        this.engine = engine;
        this.asr = asr;
        this.ocr = ocr;
        this.structurer = structurer;
        this.tickets = tickets;
        this.props = props;
        this.mapper = mapper;
    }

    /** What this installation can do (only booleans and model names; no key ever leaves the server). */
    @GetMapping("/capabilities")
    public Map<String, Object> capabilities() {
        return Map.of("asr", Map.of("configured", asr.configured(), "provider", "阿里云百炼", "model", asr.model()),
                "ocr", ocr.available(), "llm", structurer.configured(), "maxDurationMin", props.lesson().maxDurationMin());
    }

    public record AnalyzeRequest(@NotBlank String videoFileId) {}

    @PostMapping
    public ResponseEntity<Map<String, Object>> analyze(@RequestBody AnalyzeRequest req,
                                                       @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        if (req.videoFileId() == null || req.videoFileId().isBlank()) throw new ApiException(ErrorCode.VALIDATION_FAILED, "请选择视频");
        TaskEntity t = engine.submit(CurrentUser.id(), "lesson.analyze", mapper.createObjectNode(), List.of(req.videoFileId()), "PRO", key);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("taskId", t.getId(), "status", t.getStatus(), "statusUrl", "/api/tasks/" + t.getId()));
    }

    @GetMapping
    public ObjectNode list() {
        ObjectNode out = mapper.createObjectNode();
        ArrayNode items = out.putArray("items");
        for (LessonEntity l : lessons.findByOwnerId(CurrentUser.id(), PageRequest.of(0, 100, Sort.by(Sort.Direction.DESC, "updatedAt")))) {
            ObjectNode data = read(l.getDataJson());
            ObjectNode n = items.addObject();
            n.put("id", l.getId());
            n.put("title", l.getTitle());
            n.put("videoFileId", l.getVideoFileId());
            n.put("duration", data.path("duration").asDouble());
            n.put("nodes", data.path("nodes").size());
            n.put("snippets", data.path("snippets").size());
            n.put("createdAt", l.getCreatedAt());
        }
        return out;
    }

    @GetMapping("/{id}")
    public ObjectNode get(@PathVariable String id) {
        LessonEntity l = require(id);
        ObjectNode n = mapper.createObjectNode();
        n.put("id", l.getId());
        n.put("videoFileId", l.getVideoFileId());
        n.set("lesson", read(l.getDataJson()));
        n.set("transcript", readArray(l.getTranscriptJson()));
        n.set("screen", readArray(l.getOcrJson()));
        n.set("meta", read(l.getMetaJson()));
        n.put("createdAt", l.getCreatedAt());
        return n;
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        LessonEntity l = require(id);
        lessons.delete(l);
        deleteDir(dir(id));
        return ResponseEntity.noContent().build();
    }

    /** A ticket for this lesson's evidence frames (an &lt;img&gt; cannot send a bearer token). */
    @PostMapping("/{id}/ticket")
    public Map<String, Object> ticket(@PathVariable String id) {
        require(id);
        DownloadTicketService.Ticket t = tickets.issue(CurrentUser.id(), "lesson:" + id);
        return Map.of("ticket", t.value(), "expiresAt", t.expiresAt(), "frameUrl", "/api/v2/lessons/" + id + "/frames/{frame}?ticket=" + t.value());
    }

    @GetMapping(value = "/{id}/frames/{frame}", produces = MediaType.IMAGE_JPEG_VALUE)
    public ResponseEntity<byte[]> frame(@PathVariable String id, @PathVariable String frame,
                                        @RequestParam(value = "ticket", required = false) String ticket) throws IOException {
        long uid = CurrentUser.optionalId().or(() -> tickets.verify(ticket, "lesson:" + id))
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED, "链接无效或已过期"));
        lessons.findByIdAndOwnerId(id, uid).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (!FRAME.matcher(frame).matches()) throw new ApiException(ErrorCode.NOT_FOUND);
        Path p = dir(id).resolve("frames").resolve(frame);
        if (!Files.isRegularFile(p)) throw new ApiException(ErrorCode.NOT_FOUND);
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(10, TimeUnit.MINUTES).cachePrivate())
                .header("X-Content-Type-Options", "nosniff").contentType(MediaType.IMAGE_JPEG).body(Files.readAllBytes(p));
    }

    private LessonEntity require(String id) {
        return lessons.findByIdAndOwnerId(id, CurrentUser.id()).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "课程不存在或无权访问"));
    }

    private Path dir(String id) {
        return props.dataRoot().resolve("lessons").resolve(id).normalize();
    }

    private ObjectNode read(String json) {
        try {
            return json == null ? mapper.createObjectNode() : (ObjectNode) mapper.readTree(json);
        } catch (Exception e) {
            return mapper.createObjectNode();
        }
    }

    private ArrayNode readArray(String json) {
        try {
            return json == null ? mapper.createArrayNode() : (ArrayNode) mapper.readTree(json);
        } catch (Exception e) {
            return mapper.createArrayNode();
        }
    }

    private static void deleteDir(Path dir) {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        } catch (IOException ignored) {
            // best effort
        }
    }
}
