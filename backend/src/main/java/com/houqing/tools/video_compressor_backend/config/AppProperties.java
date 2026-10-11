package com.houqing.tools.video_compressor_backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.util.List;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        String dataDir,
        int retentionHours,
        Tools tools,
        Media media,
        Security security,
        Cors cors,
        Ai ai
) {
    public record Tools(String ffmpeg, String ffprobe, String exiftool, String magick,
                        String rembg, String conda, String rembgEnv, String ghostscript, String rembgModel) {}

    public record Media(int maxConcurrent, int queueCapacity, int perUserActiveLimit,
                        int ffmpegThreads, int taskTimeoutMinutes, long minFreeDiskMb) {}

    public record Security(String jwtSecret, int jwtTtlHours, boolean allowRegistration) {}

    public record Cors(List<String> allowedOrigins) {}

    /** apiKey is only ever read server-side; toString is overridden so it never lands in logs. */
    public record Ai(String apiKey, String baseUrl, String model) {
        public boolean configured() { return apiKey != null && !apiKey.isBlank(); }
        @Override public String toString() { return "Ai[baseUrl=" + baseUrl + ", model=" + model + ", apiKey=" + (configured() ? "***" : "<unset>") + "]"; }
    }

    public Path dataRoot() { return Path.of(dataDir).toAbsolutePath().normalize(); }
}
