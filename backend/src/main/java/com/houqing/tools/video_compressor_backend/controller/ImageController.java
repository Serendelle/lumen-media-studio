package com.houqing.tools.video_compressor_backend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.houqing.tools.video_compressor_backend.auth.CurrentUser;
import com.houqing.tools.video_compressor_backend.common.ApiException;
import com.houqing.tools.video_compressor_backend.common.ErrorCode;
import com.houqing.tools.video_compressor_backend.dto.ImageProcessRequestDTO;
import com.houqing.tools.video_compressor_backend.media.*;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/image")
public class ImageController {
    private final UploadService uploads;
    private final TaskEngine engine;
    private final ObjectMapper mapper;

    public ImageController(UploadService uploads, TaskEngine engine, ObjectMapper mapper) {
        this.uploads = uploads;
        this.engine = engine;
        this.mapper = mapper;
    }

    /** Original contract {uniqueFilename}. Also accepts PDFs, since the PDF tools had no upload endpoint of their own. */
    @PostMapping("/upload")
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file) {
        MediaFile mf = uploads.upload(CurrentUser.id(), file, Set.of(MediaKind.IMAGE, MediaKind.PDF));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("uniqueFilename", mf.getId());
        body.put("file", uploads.view(mf));
        return body;
    }

    @PostMapping("/process")
    public Map<String, Object> process(@Valid @RequestBody ImageProcessRequestDTO req,
                                       @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        ObjectNode p = req.getSettings() != null && req.getSettings().isObject()
                ? ((ObjectNode) req.getSettings()).deepCopy() : mapper.createObjectNode();
        p.put("tool", req.getTool());
        return TaskResponses.created(engine.submit(CurrentUser.id(), "image.process", p, req.getFilenames(), "PRO", key));
    }

    @PostMapping("/remove-background")
    public Map<String, Object> removeBackground(@RequestBody Map<String, String> payload,
                                                @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        String id = payload == null ? null : payload.get("uniqueFilename");
        if (id == null || id.isBlank()) throw new ApiException(ErrorCode.VALIDATION_FAILED, "uniqueFilename is required.");
        return TaskResponses.created(engine.submit(CurrentUser.id(), "image.removebg", mapper.createObjectNode(),
                List.of(id), "PRO", key));
    }

    @GetMapping("/progress/{taskId}")
    public TaskView progress(@PathVariable String taskId) {
        return engine.toView(engine.require(CurrentUser.id(), taskId));
    }
}
