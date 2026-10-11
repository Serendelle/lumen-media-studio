package com.houqing.tools.video_compressor_backend.dto;
import lombok.Data;

@Data
public class WatermarkRequestDTO {
    private String videoUniqueFilename;
    private String watermarkUniqueFilename;
    private double duration;
    //下面是水印的坐标哦
    private String position;
    //这里是水印的透明度
    private double opacity;
    //下面是水印的边距
    private int margin;

}
