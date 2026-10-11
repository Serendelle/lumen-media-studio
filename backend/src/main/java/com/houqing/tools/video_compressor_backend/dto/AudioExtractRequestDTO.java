package com.houqing.tools.video_compressor_backend.dto;

import lombok.Data;

@Data
public class AudioExtractRequestDTO {
    // 原始视频的唯一文件名
    private String uniqueFilename;
    // 视频时长，用于计算进度
    private double duration;
    // 目标音频格式 (e.g., "mp3", "aac", "wav")
    private String targetFormat;
}