package com.houqing.tools.video_compressor_backend.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

/**
 * Original contract kept: uniqueFilename / mode / targetSizeMB / crfValue / audioBitrate / duration / width / height.
 * duration and audioBitrate are accepted but no longer trusted — the server probes the file itself.
 * mode: "crf" | "bitrate" (original meaning: hit targetSizeMB) | "size" (alias) | "kbps" (explicit videoBitrateKbps).
 */
@Data
public class CompressRequestDTO {
    @NotBlank
    private String uniqueFilename;

    @Pattern(regexp = "crf|bitrate|size|kbps", message = "mode 仅支持 crf/bitrate/size/kbps")
    private String mode = "crf";

    @Positive @Max(100000)
    private Double targetSizeMB;

    @Min(0) @Max(51)
    private Integer crfValue;

    /** Original field (bps). Ignored for planning; use audioBitrateKbps to choose the encoded audio bitrate. */
    private long audioBitrate;
    /** Original field. Ignored: duration comes from ffprobe. */
    private double duration;

    /** Target resolution; values equal to or above the source are a no-op (never upscales). */
    @Min(0) @Max(7680)
    private int width;
    @Min(0) @Max(7680)
    private int height;

    // ---- extensions ----
    @Pattern(regexp = "original|2160p|1440p|1080p|720p|540p|480p|360p")
    private String resolution;
    @Pattern(regexp = "h264|h265")
    private String codec;
    @Min(1) @Max(120)
    private Integer fps;
    /** 0 drops audio; otherwise 32-512. */
    @Min(0) @Max(512)
    private Integer audioBitrateKbps;
    @Min(50) @Max(200000)
    private Integer videoBitrateKbps;
    @Pattern(regexp = "ultrafast|superfast|veryfast|faster|fast|medium|slow")
    private String preset;
}
