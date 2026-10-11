package com.houqing.tools.video_compressor_backend.controller;

import com.houqing.tools.video_compressor_backend.dto.AudioExtractRequestDTO;
import com.houqing.tools.video_compressor_backend.dto.TaskProgressDTO;
import com.houqing.tools.video_compressor_backend.service.AudioService;
import com.houqing.tools.video_compressor_backend.service.TaskService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/audio")
public class AudioController {

    private final AudioService audioService;
    private final TaskService taskService;

    public AudioController(AudioService audioService, TaskService taskService) {
        this.audioService = audioService;
        this.taskService = taskService;
    }

    @PostMapping("/extract")
    public ResponseEntity<?> extractAudio(@RequestBody AudioExtractRequestDTO request) {
        TaskProgressDTO task = taskService.createTask();
        audioService.extractAudio(task.getTaskId(), request);
        return ResponseEntity.ok(Map.of("taskId", task.getTaskId()));
    }

    @GetMapping("/progress/{taskId}")
    public ResponseEntity<TaskProgressDTO> getProgress(@PathVariable String taskId) {
        TaskProgressDTO task = taskService.getTask(taskId);
        if (task == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(task);
    }
}