package com.houqing.tools.video_compressor_backend.dto;

import lombok.Data;

@Data
public class MetadataModifyRequestDTO {
    private String uniqueFilename;
    // 拍摄设备, e.g., "iPhone 16 Pro"
    private String model;
    // 拍摄时间, 格式为 "yyyy:MM:dd HH:mm:ss"
    private String dateTimeOriginal;
    // 纬度
    private Double gpsLatitude;
    // 经度
    private Double gpsLongitude;
}