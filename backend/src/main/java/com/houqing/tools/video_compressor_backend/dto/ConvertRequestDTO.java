package com.houqing.tools.video_compressor_backend.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class ConvertRequestDTO {
    @NotBlank
    private String uniqueFilename;
    @NotBlank
    @Pattern(regexp = "mp4|mov|webm|mkv|gif", message = "targetFormat 仅支持 mp4/mov/webm/mkv/gif")
    private String targetFormat;
    /** Original field, ignored (server probes the duration). */
    private double duration;
    /** GIF only. */
    @Min(1) @Max(30)
    private Integer fps;
}
