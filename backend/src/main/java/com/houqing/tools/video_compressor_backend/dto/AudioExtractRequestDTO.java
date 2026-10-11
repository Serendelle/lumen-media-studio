package com.houqing.tools.video_compressor_backend.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class AudioExtractRequestDTO {
    @NotBlank
    private String uniqueFilename;
    /** Original field, ignored. */
    private double duration;
    @Pattern(regexp = "mp3|aac|m4a|wav|flac", message = "targetFormat 仅支持 mp3/aac/m4a/wav/flac")
    private String targetFormat = "mp3";
    // ---- extensions ----
    @Min(32) @Max(320)
    private Integer bitrateKbps;
    private Integer sampleRate;
    @Min(1) @Max(2)
    private Integer channels;
}
