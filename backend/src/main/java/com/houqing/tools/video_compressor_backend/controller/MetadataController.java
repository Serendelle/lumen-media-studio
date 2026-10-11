package com.houqing.tools.video_compressor_backend.controller;

import com.houqing.tools.video_compressor_backend.dto.MetadataModifyRequestDTO;
import com.houqing.tools.video_compressor_backend.service.MetadataService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/metadata")
public class MetadataController {

    private static final Logger logger = LoggerFactory.getLogger(MetadataController.class);

    private final MetadataService metadataService;

    public MetadataController(MetadataService metadataService) {
        this.metadataService = metadataService;
    }

    // 统一的上传接口，只保存文件，不分析
    @PostMapping("/upload")
    public ResponseEntity<?> uploadFile(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body("Please select a file.");
        }
        try {
            String uniqueFilename = metadataService.saveUploadedFile(file);
            return ResponseEntity.ok(Map.of("uniqueFilename", uniqueFilename));
        } catch (Exception e) {
            logger.error("!!! File upload for metadata failed: {}", file.getOriginalFilename(), e);
            return ResponseEntity.internalServerError().body("File upload failed: " + e.getMessage());
        }
    }

    // 查看元数据接口
    @PostMapping("/view")
    public ResponseEntity<?> viewMetadata(@RequestBody Map<String, String> payload) {
        String uniqueFilename = payload.get("uniqueFilename");
        try {
            Map<String, Object> metadata = metadataService.viewMetadata(uniqueFilename);
            return ResponseEntity.ok(metadata);
        } catch (Exception e) {
            logger.error("!!! Failed to view metadata for: {}", uniqueFilename, e);
            return ResponseEntity.internalServerError().body("Failed to view metadata: " + e.getMessage());
        }
    }

    // 删除元数据接口
    @PostMapping("/remove")
    public ResponseEntity<?> removeMetadata(@RequestBody Map<String, String> payload) {
        String uniqueFilename = payload.get("uniqueFilename");
        try {
            String downloadFilename = metadataService.removeMetadata(uniqueFilename);
            return ResponseEntity.ok(Map.of("downloadFilename", downloadFilename));
        } catch (Exception e) {
            logger.error("!!! Failed to remove metadata for: {}", uniqueFilename, e);
            return ResponseEntity.internalServerError().body("Failed to remove metadata: " + e.getMessage());
        }
    }

    @PostMapping("/modify")
    public ResponseEntity<?> modifyMetadata(@RequestBody MetadataModifyRequestDTO request) {
        try {
            String downloadFilename = metadataService.modifyMetadata(request);
            return ResponseEntity.ok(java.util.Map.of("downloadFilename", downloadFilename));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(java.util.Map.of("error", e.getMessage()));
        }
    }

}