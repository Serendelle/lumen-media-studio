package com.houqing.tools.video_compressor_backend.controller;

import com.houqing.tools.video_compressor_backend.config.AppProperties;
import com.houqing.tools.video_compressor_backend.media.MetadataCapabilities;
import com.houqing.tools.video_compressor_backend.media.StorageService;
import com.houqing.tools.video_compressor_backend.media.Tool;
import com.houqing.tools.video_compressor_backend.media.ToolLocator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Public, secret-free description of what this installation can actually do, so the UI shows real
 * availability (e.g. "rembg not installed") instead of offering features that would fail.
 */
@RestController
@RequestMapping("/api/system")
public class SystemController {
    private final ToolLocator tools;
    private final AppProperties props;
    private final StorageService storage;

    public SystemController(ToolLocator tools, AppProperties props, StorageService storage) {
        this.tools = tools;
        this.props = props;
        this.storage = storage;
    }

    @GetMapping("/capabilities")
    public Map<String, Object> capabilities() {
        Map<String, Object> toolMap = new LinkedHashMap<>();
        for (Tool t : Tool.values()) toolMap.put(t.name().toLowerCase(), tools.available(t));

        Map<String, Object> features = new LinkedHashMap<>();
        boolean ff = tools.available(Tool.FFMPEG) && tools.available(Tool.FFPROBE);
        features.put("video.compress", ff);
        features.put("video.convert", ff);
        features.put("video.watermark", ff);
        features.put("audio.extract", ff);
        features.put("image.process", tools.available(Tool.MAGICK));
        features.put("image.removebg", tools.available(Tool.REMBG));
        features.put("pdf.process", tools.available(Tool.MAGICK) && tools.available(Tool.GHOSTSCRIPT));
        features.put("metadata.view", tools.available(Tool.EXIFTOOL));
        features.put("metadata.modify", tools.available(Tool.EXIFTOOL) && ff);
        features.put("metadata.scrub", tools.available(Tool.EXIFTOOL) && ff);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tools", toolMap);
        body.put("features", features);
        body.put("ai", Map.of("configured", props.ai().configured(), "model", props.ai().model()));
        body.put("limits", Map.of(
                "maxConcurrentTasks", props.media().maxConcurrent(),
                "perUserActiveTasks", props.media().perUserActiveLimit(),
                "retentionHours", props.retentionHours(),
                "registrationOpen", props.security().allowRegistration()));
        body.put("metadataCapabilities", MetadataCapabilities.matrix());
        return body;
    }

    /** Adds tool availability and free disk to /actuator/health. */
    @Component("mediaTools")
    static class MediaToolsHealth implements HealthIndicator {
        private final ToolLocator tools;
        private final StorageService storage;
        private final AppProperties props;

        MediaToolsHealth(ToolLocator tools, StorageService storage, AppProperties props) {
            this.tools = tools;
            this.storage = storage;
            this.props = props;
        }

        @Override
        public Health health() {
            Map<String, Object> details = new LinkedHashMap<>();
            for (Tool t : Tool.values()) details.put(t.label(), tools.available(t) ? "available" : "missing");
            long freeMb = storage.usableSpaceMb();
            details.put("freeDiskMb", freeMb);
            boolean core = tools.available(Tool.FFMPEG) && tools.available(Tool.FFPROBE);
            boolean disk = freeMb >= props.media().minFreeDiskMb();
            return (core && disk ? Health.up() : Health.down()).withDetails(details).build();
        }
    }
}
