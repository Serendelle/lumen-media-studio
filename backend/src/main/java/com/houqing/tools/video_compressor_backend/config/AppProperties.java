package com.houqing.tools.video_compressor_backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

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
        Ai ai,
        Asr asr,
        Lesson lesson
) {
    public record Tools(String ffmpeg, String ffprobe, String exiftool, String magick,
                        String rembg, String conda, String rembgEnv, String ghostscript, String rembgModel,
                        String soffice, String chromium, String ocrPython) {}

    public record Media(int maxConcurrent, int queueCapacity, int perUserActiveLimit,
                        int ffmpegThreads, int taskTimeoutMinutes, long minFreeDiskMb) {}

    public record Security(String jwtSecret, int jwtTtlHours, boolean allowRegistration) {}

    public record Cors(List<String> allowedOrigins) {}

    /** apiKey is only ever read server-side; toString is overridden so it never lands in logs. */
    public record Ai(String apiKey, String baseUrl, String model, String proxy) {
        /** The .env.example placeholder ("sk-your-key-here") counts as not configured, so it never triggers 401s. */
        public boolean configured() {
            return apiKey != null && !apiKey.isBlank() && !apiKey.contains("your-key") && !apiKey.equalsIgnoreCase("change-me");
        }
        @Override public String toString() { return "Ai[baseUrl=" + baseUrl + ", model=" + model + ", proxy=" + proxy + ", apiKey=" + (configured() ? "***" : "<unset>") + "]"; }
    }

    /** Cloud speech recognition (Alibaba Cloud Model Studio / DashScope). The key is server-side only. */
    public record Asr(String apiKey, String baseUrl, String model, String proxy) {
        @ConstructorBinding
        public Asr {}

        public Asr(String apiKey, String baseUrl, String model) { this(apiKey, baseUrl, model, ""); }

        public boolean configured() {
            return apiKey != null && !apiKey.isBlank() && !apiKey.contains("your-key");
        }
        @Override public String toString() { return "Asr[baseUrl=" + baseUrl + ", model=" + model + ", apiKey=" + (configured() ? "***" : "<unset>") + "]"; }
    }

    /** Learning player: keyframe sampling for OCR and an upper bound on lesson length. */
    public record Lesson(int keyframeIntervalSec, int maxDurationMin) {}

    public Path dataRoot() { return Path.of(dataDir).toAbsolutePath().normalize(); }
}
