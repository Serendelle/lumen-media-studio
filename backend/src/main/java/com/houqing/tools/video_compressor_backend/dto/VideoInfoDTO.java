// DTO 修正版
package com.houqing.tools.video_compressor_backend.dto;

import lombok.Data;

@Data
public class VideoInfoDTO {
    private String originalFilename;
    private String formatName;
    private double duration;
    private long size;
    private long videoBitrate;
    private long audioBitrate;
    private int width;
    private int height;
}