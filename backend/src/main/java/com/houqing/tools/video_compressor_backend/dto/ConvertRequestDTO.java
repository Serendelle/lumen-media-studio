package com.houqing.tools.video_compressor_backend.dto;
import lombok.Data;

@Data

public class ConvertRequestDTO {
    private String uniqueFilename;
    private String targetFormat;
    // 下面是视屏的时长可要可不要 用来计算进度
    private double duration;
}
