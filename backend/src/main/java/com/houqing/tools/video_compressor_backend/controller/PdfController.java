package com.houqing.tools.video_compressor_backend.controller;

import com.houqing.tools.video_compressor_backend.dto.PdfProcessRequestDTO;
import com.houqing.tools.video_compressor_backend.dto.TaskProgressDTO;
import com.houqing.tools.video_compressor_backend.service.PdfService;
import com.houqing.tools.video_compressor_backend.service.TaskService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/pdf")
public class PdfController {

    private final PdfService pdfService;
    private final TaskService taskService;

    public PdfController(PdfService pdfService, TaskService taskService) {
        this.pdfService = pdfService;
        this.taskService = taskService;
    }

    // 统一的 PDF 处理接口
    @PostMapping("/process")
    public ResponseEntity<?> processPdf(@RequestBody PdfProcessRequestDTO request) {
        TaskProgressDTO task = taskService.createTask();
        pdfService.processPdf(task.getTaskId(), request);
        return ResponseEntity.ok(Map.of("taskId", task.getTaskId()));
    }

    // 进度查询接口 (复用 TaskService)
    @GetMapping("/progress/{taskId}")
    public ResponseEntity<TaskProgressDTO> getProgress(@PathVariable String taskId) {
        TaskProgressDTO task = taskService.getTask(taskId);
        if (task == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(task);
    }
}