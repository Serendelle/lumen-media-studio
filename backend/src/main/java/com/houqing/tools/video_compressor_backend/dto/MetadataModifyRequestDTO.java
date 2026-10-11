package com.houqing.tools.video_compressor_backend.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class MetadataModifyRequestDTO {
    @NotBlank
    private String uniqueFilename;
    @Size(max = 256)
    private String model;
    /** yyyy:MM:dd HH:mm:ss */
    @Pattern(regexp = "^\\d{4}:\\d{2}:\\d{2} \\d{2}:\\d{2}:\\d{2}$", message = "时间格式应为 yyyy:MM:dd HH:mm:ss")
    private String dateTimeOriginal;
    @DecimalMin("-90") @DecimalMax("90")
    private Double gpsLatitude;
    @DecimalMin("-180") @DecimalMax("180")
    private Double gpsLongitude;
    // ---- extensions ----
    @Size(max = 256) private String title;
    @Size(max = 256) private String artist;
    @Size(max = 256) private String comment;
    @Size(max = 256) private String copyright;
    @Size(max = 256) private String make;
    private Boolean clearGps;
}
