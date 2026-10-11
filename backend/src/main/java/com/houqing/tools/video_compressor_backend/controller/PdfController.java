package com.houqing.tools.video_compressor_backend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.houqing.tools.video_compressor_backend.auth.CurrentUser;
import com.houqing.tools.video_compressor_backend.dto.PdfProcessRequestDTO;
import com.houqing.tools.video_compressor_backend.media.TaskEngine;
import com.houqing.tools.video_compressor_backend.media.TaskView;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/pdf")
public class PdfController {
    private final TaskEngine engine;
    private final ObjectMapper mapper;

    public PdfController(TaskEngine engine, ObjectMapper mapper) {
        this.engine = engine;
        this.mapper = mapper;
    }

    @PostMapping("/process")
    public Map<String, Object> process(@Valid @RequestBody PdfProcessRequestDTO req,
                                       @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        ObjectNode p = req.getSettings() != null && req.getSettings().isObject()
                ? ((ObjectNode) req.getSettings()).deepCopy() : mapper.createObjectNode();
        p.put("tool", req.getTool());
        return TaskResponses.created(engine.submit(CurrentUser.id(), "pdf.process", p, req.getFilenames(), "PRO", key));
    }

    @GetMapping("/progress/{taskId}")
    public TaskView progress(@PathVariable String taskId) {
        return engine.toView(engine.require(CurrentUser.id(), taskId));
    }
}
