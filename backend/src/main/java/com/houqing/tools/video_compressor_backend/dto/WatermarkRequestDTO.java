package com.houqing.tools.video_compressor_backend.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class WatermarkRequestDTO {
    @NotBlank
    private String videoUniqueFilename;
    @NotBlank
    private String watermarkUniqueFilename;
    /** Original field, ignored. */
    private double duration;
    @Pattern(regexp = "top-left|top-right|bottom-left|bottom-right|center|top-center|bottom-center|center-left|center-right")
    private String position = "bottom-right";
    @DecimalMin("0.0") @DecimalMax("1.0")
    private double opacity = 0.75;
    @Min(0) @Max(500)
    private int margin = 24;
}
