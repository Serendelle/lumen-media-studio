package com.houqing.tools.video_compressor_backend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.houqing.tools.video_compressor_backend.auth.CurrentUser;
import com.houqing.tools.video_compressor_backend.common.ApiException;
import com.houqing.tools.video_compressor_backend.common.ErrorCode;
import com.houqing.tools.video_compressor_backend.dto.CompressRequestDTO;
import com.houqing.tools.video_compressor_backend.dto.ConvertRequestDTO;
import com.houqing.tools.video_compressor_backend.dto.WatermarkRequestDTO;
import com.houqing.tools.video_compressor_backend.media.*;
import com.houqing.tools.video_compressor_backend.media.handler.VideoCompressHandler;
import jakarta.validation.Valid;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/video")
public class VideoController {

    private final UploadService uploads;
    private final StorageService storage;
    private final TaskEngine engine;
    private final MediaProbe probe;
    private final FileStreamer streamer;
    private final ObjectMapper mapper;

    public VideoController(UploadService uploads, StorageService storage, TaskEngine engine, MediaProbe probe,
                           FileStreamer streamer, ObjectMapper mapper) {
        this.uploads = uploads;
        this.storage = storage;
        this.engine = engine;
        this.probe = probe;
        this.streamer = streamer;
        this.mapper = mapper;
    }

    /** Original contract: {videoInfo{...}, uniqueFilename}. Extra fields are additive. */
    @PostMapping("/upload")
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file) {
        MediaFile mf = uploads.upload(CurrentUser.id(), file, Set.of(MediaKind.VIDEO));
        FileView fv = uploads.view(mf);
        JsonNode info = fv.info() == null ? mapper.createObjectNode() : fv.info();
        Map<String, Object> videoInfo = new LinkedHashMap<>();
        videoInfo.put("originalFilename", mf.getOriginalName());
        videoInfo.put("formatName", info.path("formatName").asText(""));
        videoInfo.put("duration", info.path("duration").asDouble(0));
        videoInfo.put("size", mf.getSizeBytes());
        videoInfo.put("width", info.path("width").asInt(0));
        videoInfo.put("height", info.path("height").asInt(0));
        videoInfo.put("videoBitrate", info.path("videoBitrate").asLong(0));
        videoInfo.put("audioBitrate", info.path("audioBitrate").asLong(0));
        videoInfo.put("fps", info.path("fps").asDouble(0));
        videoInfo.put("videoCodec", info.path("videoCodec").asText(null));
        videoInfo.put("audioCodec", info.path("audioCodec").asText(null));
        videoInfo.put("hasAudio", info.path("hasAudio").asBoolean(false));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("videoInfo", videoInfo);
        body.put("uniqueFilename", mf.getId());
        body.put("file", fv);
        return body;
    }

    @PostMapping("/compress")
    public Map<String, Object> compress(@Valid @RequestBody CompressRequestDTO req,
                                        @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        TaskEntity t = engine.submit(CurrentUser.id(), "video.compress", compressParams(req),
                List.of(req.getUniqueFilename()), "PRO", key);
        return TaskResponses.created(t);
    }

    /** Feasibility check for a target size before submitting (no processing). */
    @PostMapping("/compress/estimate")
    public CompressionPlanner.Plan estimate(@Valid @RequestBody CompressRequestDTO req) {
        if (req.getTargetSizeMB() == null) throw new ApiException(ErrorCode.VALIDATION_FAILED, "请提供 targetSizeMB");
        MediaFile mf = storage.require(CurrentUser.id(), req.getUniqueFilename());
        if (mf.kind() != MediaKind.VIDEO) throw new ApiException(ErrorCode.VALIDATION_FAILED, "需要视频文件");
        return VideoCompressHandler.estimate(probe.probe(storage.pathOf(mf)), compressParams(req));
    }

    @PostMapping("/convert")
    public Map<String, Object> convert(@Valid @RequestBody ConvertRequestDTO req,
                                       @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        ObjectNode p = mapper.createObjectNode();
        p.put("targetFormat", req.getTargetFormat());
        if (req.getFps() != null) p.put("fps", req.getFps());
        return TaskResponses.created(engine.submit(CurrentUser.id(), "video.convert", p,
                List.of(req.getUniqueFilename()), "PRO", key));
    }

    @PostMapping("/watermark")
    public Map<String, Object> watermark(@Valid @RequestBody WatermarkRequestDTO req,
                                         @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        ObjectNode p = mapper.createObjectNode();
        p.put("position", req.getPosition());
        p.put("opacity", req.getOpacity());
        p.put("margin", req.getMargin());
        return TaskResponses.created(engine.submit(CurrentUser.id(), "video.watermark", p,
                List.of(req.getVideoUniqueFilename(), req.getWatermarkUniqueFilename()), "PRO", key));
    }

    @GetMapping("/progress/{taskId}")
    public TaskView progress(@PathVariable String taskId) {
        return engine.toView(engine.require(CurrentUser.id(), taskId));
    }

    /** Original path. Requires a bearer token or ?ticket= from POST /api/files/{id}/ticket. */
    @GetMapping("/download/{filename}")
    public ResponseEntity<Resource> download(@PathVariable String filename,
                                             @RequestParam(value = "ticket", required = false) String ticket) {
        return streamer.stream(filename, ticket, true);
    }

    /** Maps the original DTO (crf / bitrate-as-target-size) onto handler params. */
    ObjectNode compressParams(CompressRequestDTO r) {
        ObjectNode p = mapper.createObjectNode();
        String mode = r.getMode() == null ? "crf" : r.getMode();
        switch (mode) {
            case "bitrate", "size" -> {
                if (r.getTargetSizeMB() != null) {
                    p.put("mode", "size");
                    p.put("targetSizeMB", r.getTargetSizeMB());
                } else if (r.getVideoBitrateKbps() != null) {
                    p.put("mode", "bitrate");
                    p.put("videoBitrateKbps", r.getVideoBitrateKbps());
                } else {
                    throw new ApiException(ErrorCode.VALIDATION_FAILED, "bitrate 模式需要 targetSizeMB");
                }
            }
            case "kbps" -> {
                if (r.getVideoBitrateKbps() == null) throw new ApiException(ErrorCode.VALIDATION_FAILED, "请提供 videoBitrateKbps");
                p.put("mode", "bitrate");
                p.put("videoBitrateKbps", r.getVideoBitrateKbps());
            }
            default -> {
                p.put("mode", "crf");
                p.put("crf", r.getCrfValue() == null ? 23 : r.getCrfValue());
            }
        }
        if (r.getResolution() != null) {
            p.put("resolution", r.getResolution());
        } else if (r.getWidth() > 0 || r.getHeight() > 0) {
            ObjectNode res = p.putObject("resolution");
            res.put("width", r.getWidth());
            res.put("height", r.getHeight());
        }
        if (r.getCodec() != null) p.put("codec", r.getCodec());
        if (r.getFps() != null) p.put("fps", r.getFps());
        if (r.getAudioBitrateKbps() != null) p.put("audioBitrateKbps", r.getAudioBitrateKbps());
        if (r.getPreset() != null) p.put("preset", r.getPreset());
        return p;
    }
}
