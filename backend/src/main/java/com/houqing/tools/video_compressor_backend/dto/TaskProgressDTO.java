package com.houqing.tools.video_compressor_backend.dto;

import lombok.Data;

@Data
public class TaskProgressDTO {
    private String taskId;
    private String status;
    private int progress = 0; // 0-100
    private String message = "任务已提交，等待处理...";
    private String downloadFilename;
}