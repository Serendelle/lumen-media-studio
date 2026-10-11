package com.houqing.tools.video_compressor_backend.dto;

import lombok.Data;

@Data
public class CompressRequestDTO {
    private String uniqueFilename;
    // 新增：压缩模式, "crf" 或 "bitrate"
    private String mode;

    // --- Bitrate 模式专用 ---
    private Double targetSizeMB;

    // --- CRF 模式专用 ---
    private Integer crfValue;

    // CRF  也需要下面的数据
    private long audioBitrate;
    private double duration;
    // 关于分辨率的问题
    private int width;
    private int height;
}