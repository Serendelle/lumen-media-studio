package com.houqing.tools.video_compressor_backend.controller;

import com.houqing.tools.video_compressor_backend.dto.ImageProcessRequestDTO;
import com.houqing.tools.video_compressor_backend.dto.TaskProgressDTO;
import com.houqing.tools.video_compressor_backend.service.ImageService;
import com.houqing.tools.video_compressor_backend.service.TaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/image") // 新的根路径 /api/image
public class ImageController {

    private static final Logger logger = LoggerFactory.getLogger(ImageController.class);

    private final ImageService imageService;
    private final TaskService taskService;

    public ImageController(ImageService imageService, TaskService taskService) {
        this.imageService = imageService;
        this.taskService = taskService;
    }

    // 图片上传接口 (复用 VideoController 的逻辑，但路径不同)
    @PostMapping("/upload")
    public ResponseEntity<?> uploadImage(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body("Please select a file to upload.");
        }
        try {
            // 调用 ImageService 的保存方法
            String uniqueFilename = imageService.saveUploadedFile(file);
            return ResponseEntity.ok(Map.of("uniqueFilename", uniqueFilename));
        } catch (Exception e) {
            logger.error("!!! Image upload failed: {}", file.getOriginalFilename(), e);
            return ResponseEntity.internalServerError().body("Image upload failed: " + e.getMessage());
        }
    }

    // 统一的图片处理接口
    @PostMapping("/process")
    public ResponseEntity<?> processImages(@RequestBody ImageProcessRequestDTO request) {
        TaskProgressDTO task = taskService.createTask();
        // 启动异步处理任务
        imageService.processImages(task.getTaskId(), request);
        return ResponseEntity.ok(Map.of("taskId", task.getTaskId()));
    }

    //扣除背景的接口
    @PostMapping("/remove-background")
    public ResponseEntity<?> removeBackground(@RequestBody Map<String, String> payload) {
        String uniqueFilename = payload.get("uniqueFilename");
        if (uniqueFilename == null || uniqueFilename.isEmpty()) {
            return ResponseEntity.badRequest().body("uniqueFilename is required.");
        }

        TaskProgressDTO task = taskService.createTask();
        imageService.removeBackground(task.getTaskId(), uniqueFilename);
        return ResponseEntity.ok(Map.of("taskId", task.getTaskId()));
    }

    // 进度查询接口
    @GetMapping("/progress/{taskId}")
    public ResponseEntity<TaskProgressDTO> getProgress(@PathVariable String taskId) {
        TaskProgressDTO task = taskService.getTask(taskId);
        if (task == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(task);
    }
}