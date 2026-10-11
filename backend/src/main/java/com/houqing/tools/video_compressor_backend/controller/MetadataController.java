package com.houqing.tools.video_compressor_backend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.houqing.tools.video_compressor_backend.auth.CurrentUser;
import com.houqing.tools.video_compressor_backend.common.ApiException;
import com.houqing.tools.video_compressor_backend.common.ErrorCode;
import com.houqing.tools.video_compressor_backend.dto.MetadataModifyRequestDTO;
import com.houqing.tools.video_compressor_backend.media.*;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/metadata")
public class MetadataController {
    /** remove/modify kept their original synchronous contract; long jobs fall back to 202 + taskId. */
    static final Duration SYNC_WAIT = Duration.ofSeconds(90);

    private final UploadService uploads;
    private final MetadataService metadata;
    private final TaskEngine engine;
    private final ObjectMapper mapper;

    public MetadataController(UploadService uploads, MetadataService metadata, TaskEngine engine, ObjectMapper mapper) {
        this.uploads = uploads;
        this.metadata = metadata;
        this.engine = engine;
        this.mapper = mapper;
    }

    @PostMapping("/upload")
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file) {
        MediaFile mf = uploads.upload(CurrentUser.id(), file, Set.of(MediaKind.IMAGE, MediaKind.VIDEO, MediaKind.AUDIO));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("uniqueFilename", mf.getId());
        body.put("file", uploads.view(mf));
        body.put("capability", MetadataCapabilities.of(StorageService.extensionOf(mf.getStoredName())));
        return body;
    }

    @PostMapping("/view")
    public Map<String, Object> view(@RequestBody Map<String, String> payload) {
        return metadata.view(CurrentUser.id(), required(payload));
    }

    /** {uniqueFilename, mode?: "all"|"location"} → {downloadFilename, taskId, result{verified, remainingLocationTags}} */
    @PostMapping("/remove")
    public ResponseEntity<Map<String, Object>> remove(@RequestBody Map<String, String> payload,
                                                      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        long uid = CurrentUser.id();
        ObjectNode p = mapper.createObjectNode();
        p.put("mode", payload.getOrDefault("mode", "all"));
        TaskEntity t = engine.submit(uid, "metadata.scrub", p, List.of(required(payload)), "PRO", key);
        return TaskResponses.syncResult(engine, engine.awaitTerminal(uid, t.getId(), SYNC_WAIT));
    }

    @PostMapping("/modify")
    public ResponseEntity<Map<String, Object>> modify(@Valid @RequestBody MetadataModifyRequestDTO req,
                                                      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        long uid = CurrentUser.id();
        ObjectNode p = mapper.createObjectNode();
        putIf(p, "title", req.getTitle());
        putIf(p, "artist", req.getArtist());
        putIf(p, "comment", req.getComment());
        putIf(p, "copyright", req.getCopyright());
        putIf(p, "make", req.getMake());
        putIf(p, "model", req.getModel());
        putIf(p, "dateTimeOriginal", req.getDateTimeOriginal());
        if (req.getGpsLatitude() != null) p.put("gpsLatitude", req.getGpsLatitude());
        if (req.getGpsLongitude() != null) p.put("gpsLongitude", req.getGpsLongitude());
        if (Boolean.TRUE.equals(req.getClearGps())) p.put("clearGps", true);
        TaskEntity t = engine.submit(uid, "metadata.modify", p, List.of(req.getUniqueFilename()), "PRO", key);
        return TaskResponses.syncResult(engine, engine.awaitTerminal(uid, t.getId(), SYNC_WAIT));
    }

    /** Per-format writable fields, so the UI only offers what the tools can actually write. */
    @GetMapping("/capabilities")
    public Map<String, MetadataCapabilities.Capability> capabilities() {
        return MetadataCapabilities.matrix();
    }

    private static String required(Map<String, String> payload) {
        String id = payload == null ? null : payload.get("uniqueFilename");
        if (id == null || id.isBlank()) throw new ApiException(ErrorCode.VALIDATION_FAILED, "uniqueFilename is required.");
        return id;
    }

    private static void putIf(ObjectNode p, String k, String v) {
        if (v != null && !v.isBlank()) p.put(k, v);
    }
}
