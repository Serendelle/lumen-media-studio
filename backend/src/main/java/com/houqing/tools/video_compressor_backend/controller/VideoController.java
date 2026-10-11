package com.houqing.tools.video_compressor_backend.controller;

import com.houqing.tools.video_compressor_backend.dto.TaskProgressDTO;
import com.houqing.tools.video_compressor_backend.dto.VideoInfoDTO;
import com.houqing.tools.video_compressor_backend.dto.CompressRequestDTO;
import com.houqing.tools.video_compressor_backend.dto.ConvertRequestDTO;
import com.houqing.tools.video_compressor_backend.service.TaskService;
import com.houqing.tools.video_compressor_backend.service.VideoService;
import com.houqing.tools.video_compressor_backend.dto.WatermarkRequestDTO;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

@RestController
@RequestMapping("/api/video")
public class VideoController {

    private final VideoService videoService;
    private final TaskService taskService;

    private static final Logger logger = LoggerFactory.getLogger(VideoController.class);

    public VideoController(VideoService videoService, TaskService taskService) {
        this.videoService = videoService;
        this.taskService = taskService;
    }

    @PostMapping("/upload")
    public ResponseEntity<?> uploadAndAnalyzeVideo(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body("请选择一个文件上传");
        }

        logger.info("接收到文件 {}", file.getOriginalFilename());
        try {
            Map<String, Object> result = videoService.analyzeVideo(file);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            logger.error("分析出错：{}", file.getOriginalFilename(), e);
            return ResponseEntity.internalServerError().body("分析错误，请检查日志：" + e.getClass().getSimpleName());
        }
    }
    //压缩接口
    @PostMapping("/compress")
    public ResponseEntity<?> compressVideo(@RequestBody CompressRequestDTO request) {
        TaskProgressDTO task = taskService.createTask();
        videoService.compressVideo(task.getTaskId(), request);
        return ResponseEntity.ok(Map.of("taskId", task.getTaskId()));
    }

    // 格式转换接口
    @PostMapping("/convert")
    public ResponseEntity<?> convertVideoFormat(@RequestBody ConvertRequestDTO request) {
        TaskProgressDTO task = taskService.createTask();
        videoService.convertVideoFormat(task.getTaskId(), request);
        return ResponseEntity.ok(Map.of("taskId", task.getTaskId()));
    }

    //水印接口
    @PostMapping("/watermark")
    public ResponseEntity<?> watermarkVideo(@RequestBody WatermarkRequestDTO request) {
        TaskProgressDTO task = taskService.createTask();
        videoService.addWatermark(task.getTaskId(), request);
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

    @GetMapping("/download/{filename}")
    public ResponseEntity<Resource> downloadFile(@PathVariable String filename) {
        try {
            Path file = Paths.get(videoService.getOutputDir()).resolve(filename).normalize();
            Resource resource = new UrlResource(file.toUri());

            if (resource.exists() || resource.isReadable()) {
                String originalFilename = resource.getFilename();
                String encodedFilename = URLEncoder.encode(originalFilename, StandardCharsets.UTF_8);
                String contentDisposition = "attachment; filename*=UTF-8''" + encodedFilename;

                return ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition)
                        .body(resource);
            } else {
                throw new RuntimeException("找不到文件: " + filename);
            }
        } catch (Exception e) {
            logger.error("下载文件失败: {}", filename, e);
            return ResponseEntity.internalServerError().build();
        }
    }
}
