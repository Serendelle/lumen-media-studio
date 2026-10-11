package com.houqing.tools.video_compressor_backend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.houqing.tools.video_compressor_backend.auth.CurrentUser;
import com.houqing.tools.video_compressor_backend.dto.AudioExtractRequestDTO;
import com.houqing.tools.video_compressor_backend.media.TaskEngine;
import com.houqing.tools.video_compressor_backend.media.TaskView;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/audio")
public class AudioController {
    private final TaskEngine engine;
    private final ObjectMapper mapper;

    public AudioController(TaskEngine engine, ObjectMapper mapper) {
        this.engine = engine;
        this.mapper = mapper;
    }

    @PostMapping("/extract")
    public Map<String, Object> extract(@Valid @RequestBody AudioExtractRequestDTO req,
                                       @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        ObjectNode p = mapper.createObjectNode();
        p.put("format", req.getTargetFormat() == null ? "mp3" : req.getTargetFormat());
        if (req.getBitrateKbps() != null) p.put("bitrateKbps", req.getBitrateKbps());
        if (req.getSampleRate() != null) p.put("sampleRate", req.getSampleRate());
        if (req.getChannels() != null) p.put("channels", req.getChannels());
        return TaskResponses.created(engine.submit(CurrentUser.id(), "audio.extract", p,
                List.of(req.getUniqueFilename()), "PRO", key));
    }

    @GetMapping("/progress/{taskId}")
    public TaskView progress(@PathVariable String taskId) {
        return engine.toView(engine.require(CurrentUser.id(), taskId));
    }
}
