package com.houqing.tools.video_compressor_backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.houqing.tools.video_compressor_backend.dto.CompressRequestDTO;
import com.houqing.tools.video_compressor_backend.dto.ConvertRequestDTO;
import com.houqing.tools.video_compressor_backend.dto.VideoInfoDTO;
import com.houqing.tools.video_compressor_backend.dto.WatermarkRequestDTO; // 新增
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class VideoService {

    @Value("${file.upload-dir}")
    private String uploadDir;

    @Value("${file.output-dir}")
    private String outputDir;

    @Value("${ffmpeg.ffprobe-path}")
    private String ffprobePath;

    @Value("${ffmpeg.ffmpeg-path}")
    private String ffmpegPath;

    private static final Logger logger = LoggerFactory.getLogger(VideoService.class);

    private final TaskService taskService;

    public VideoService(TaskService taskService) {
        this.taskService = taskService;
    }

    public Map<String, Object> analyzeVideo(MultipartFile file) throws Exception {
        String originalFilename = file.getOriginalFilename();
        String uniqueFilename = UUID.randomUUID().toString() + "_" + originalFilename;

        Path uploadPath = Paths.get(uploadDir);
        if (!Files.exists(uploadPath)) {
            Files.createDirectories(uploadPath);
        }
        Path filePath = uploadPath.resolve(uniqueFilename);
        file.transferTo(filePath.toFile());

        ProcessBuilder processBuilder = new ProcessBuilder(
                ffprobePath, "-v", "quiet", "-print_format", "json",
                "-show_format", "-show_streams", filePath.toString()
        );
        processBuilder.redirectErrorStream(true);
        Process process = processBuilder.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line);
            }
        }
        process.waitFor(1, TimeUnit.MINUTES);

        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode rootNode = objectMapper.readTree(output.toString());
        JsonNode formatNode = rootNode.path("format");
        JsonNode videoStreamNode = null;
        JsonNode audioStreamNode = null;
        VideoInfoDTO dto = new VideoInfoDTO();

        for (JsonNode stream : rootNode.path("streams")) {
            if ("video".equals(stream.path("codec_type").asText())) {
                videoStreamNode = stream;
                dto.setWidth(videoStreamNode.path("width").asInt());
                dto.setHeight(videoStreamNode.path("height").asInt());
            } else if ("audio".equals(stream.path("codec_type").asText())) {
                audioStreamNode = stream;
            }
        }

        dto.setOriginalFilename(originalFilename);
        dto.setFormatName(formatNode.path("format_name").asText());
        dto.setDuration(formatNode.path("duration").asDouble());
        dto.setSize(formatNode.path("size").asLong());
        if (videoStreamNode != null) {
            dto.setVideoBitrate(videoStreamNode.path("bit_rate").asLong(0));
        }
        if (audioStreamNode != null) {
            dto.setAudioBitrate(audioStreamNode.path("bit_rate").asLong(0));
        }

        Map<String, Object> result = new HashMap<>();
        result.put("videoInfo", dto);
        result.put("uniqueFilename", uniqueFilename);
        return result;
    }

    @Async
    public void compressVideo(String taskId, CompressRequestDTO request) {
        Path inputFilePath = Paths.get(uploadDir).resolve(request.getUniqueFilename());
        String outputFilename = "compressed_" + request.getUniqueFilename();
        Path outputFilePath = Paths.get(outputDir).resolve(outputFilename);

        try {
            ProcessBuilder processBuilder;

            if ("crf".equalsIgnoreCase(request.getMode())) {
                int crf = (request.getCrfValue() != null
                        && request.getCrfValue() >= 0
                        && request.getCrfValue() <= 51)
                        ? request.getCrfValue()
                        : 23;

                processBuilder = new ProcessBuilder(
                        ffmpegPath, "-y", "-i", inputFilePath.toString(),
                        "-c:v", "libx264",
                        "-crf", String.valueOf(crf),
                        "-preset", "medium",
                        "-c:a", "copy",
                        "-progress", "pipe:1",
                        outputFilePath.toString()
                );
            } else if ("bitrate".equalsIgnoreCase(request.getMode())) {
                long newVideoBitrateKbps = calculateBitrate(request);

                processBuilder = new ProcessBuilder(
                        ffmpegPath, "-y", "-i", inputFilePath.toString(),
                        "-b:v", newVideoBitrateKbps + "k",
                        "-c:a", "copy",
                        "-progress", "pipe:1",
                        outputFilePath.toString()
                );
            } else {
                throw new IllegalArgumentException("未知的压缩模式: " + request.getMode());
            }

            logger.info("Executing FFmpeg for task {}: {}", taskId, String.join(" ", processBuilder.command()));

            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                double totalDurationInMicro = request.getDuration() * 1_000_000;

                while ((line = reader.readLine()) != null) {
                    logger.debug("FFMPEG ({}): {}", taskId, line);
                    if (line.trim().startsWith("out_time_ms=")) {
                        long currentTimeInMicro = Long.parseLong(line.trim().substring(12));
                        if (currentTimeInMicro > 0) {
                            int progress = (int) ((currentTimeInMicro / totalDurationInMicro) * 100);
                            taskService.updateTaskProgress(taskId, progress, "正在处理视频: " + progress + "%");
                        }
                    }
                }
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new RuntimeException("FFmpeg 进程以错误码退出: " + exitCode);
            }

            taskService.completeTask(taskId, outputFilename);

        } catch (Exception e) {
            logger.error("压缩任务 {} 失败", taskId, e);
            taskService.failTask(taskId, e.getMessage());
        }
    }

    @Async
    public void convertVideoFormat(String taskId, ConvertRequestDTO request) {
        Path inputFilePath = Paths.get(uploadDir).resolve(request.getUniqueFilename());

        String baseName = request.getUniqueFilename();
        int lastDotIndex = baseName.lastIndexOf('.');
        if (lastDotIndex != -1) {
            baseName = baseName.substring(0, lastDotIndex);
        }
        String outputFilename = "converted_" + baseName + "." + request.getTargetFormat();
        Path outputDirPath = Paths.get(outputDir);
        try {
            if (!Files.exists(outputDirPath)) {
                Files.createDirectories(outputDirPath);
            }
        } catch (Exception e) {
            logger.error("创建输出目录失败", e);
        }
        Path outputFilePath = outputDirPath.resolve(outputFilename);

        try {
            ProcessBuilder processBuilder = new ProcessBuilder(
                    ffmpegPath,
                    "-y",
                    "-i", inputFilePath.toString(),
                    "-progress", "pipe:1",
                    outputFilePath.toString()
            );

            logger.info("Executing FFmpeg for task {}: {}", taskId, String.join(" ", processBuilder.command()));

            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                double totalDurationInMicro = request.getDuration() * 1_000_000;

                while ((line = reader.readLine()) != null) {
                    logger.debug("FFMPEG ({}): {}", taskId, line);
                    if (line.trim().startsWith("out_time_ms=")) {
                        long currentTimeInMicro = Long.parseLong(line.trim().substring(12));
                        if (currentTimeInMicro > 0) {
                            int progress = (int) ((currentTimeInMicro / totalDurationInMicro) * 100);
                            taskService.updateTaskProgress(taskId, progress,
                                    "正在转换为 " + request.getTargetFormat().toUpperCase() + ": " + progress + "%");
                        }
                    }
                }
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new RuntimeException("FFmpeg 进程以错误码退出: " + exitCode);
            }

            taskService.completeTask(taskId, outputFilename);

        } catch (Exception e) {
            logger.error("格式转换任务 {} 失败", taskId, e);
            taskService.failTask(taskId, e.getMessage());
        }
    }

    // 新增：添加水印
    @Async
    public void addWatermark(String taskId, WatermarkRequestDTO request) {
        Path videoFilePath = Paths.get(uploadDir).resolve(request.getVideoUniqueFilename());
        Path watermarkFilePath = Paths.get(uploadDir).resolve(request.getWatermarkUniqueFilename());

        String outputFilename = "watermarked_" + request.getVideoUniqueFilename();
        Path outputDirPath = Paths.get(outputDir);
        try {
            if (!Files.exists(outputDirPath)) {
                Files.createDirectories(outputDirPath);
            }
        } catch (Exception e) {
            logger.error("创建输出目录失败", e);
        }
        Path outputFilePath = outputDirPath.resolve(outputFilename);

        try {
            // 1. 位置 -> overlay 表达式
            String marginStr = String.valueOf(request.getMargin());
            String overlayExpression;
            switch (request.getPosition() == null ? "" : request.getPosition().toLowerCase()) {
                case "top-left":
                    overlayExpression = marginStr + ":" + marginStr;
                    break;
                case "top-right":
                    overlayExpression = "W-w-" + marginStr + ":" + marginStr;
                    break;
                case "bottom-left":
                    overlayExpression = marginStr + ":H-h-" + marginStr;
                    break;
                case "center":
                    overlayExpression = "(W-w)/2:(H-h)/2";
                    break;
                case "top-center":
                    overlayExpression = "(W-w)/2:" + marginStr;
                    break;
                case "bottom-center":
                    overlayExpression = "(W-w)/2:H-h-" + marginStr;
                    break;
                case "center-left":
                    overlayExpression = marginStr + ":(H-h)/2";
                    break;
                case "center-right":
                    overlayExpression = "W-w-" + marginStr + ":(H-h)/2";
                    break;
                case "bottom-right":
                default:
                    overlayExpression = "W-w-" + marginStr + ":H-h-" + marginStr;
                    break;
            }

            // 2. 滤镜
            double opacity = request.getOpacity();
            if (opacity < 0) opacity = 0;
            if (opacity > 1) opacity = 1;

            String filterComplex = String.format(
                    "[1:v]colorchannelmixer=aa=%.2f[wm];[0:v][wm]overlay=%s",
                    opacity,
                    overlayExpression
            );

            ProcessBuilder processBuilder = new ProcessBuilder(
                    ffmpegPath,
                    "-y",
                    "-i", videoFilePath.toString(),
                    "-i", watermarkFilePath.toString(),
                    "-filter_complex", filterComplex,
                    "-c:a", "copy",
                    "-progress", "pipe:1",
                    outputFilePath.toString()
            );

            logger.info("Executing FFmpeg for task {}: {}", taskId, String.join(" ", processBuilder.command()));

            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                double totalDurationInMicro = request.getDuration() * 1_000_000;

                while ((line = reader.readLine()) != null) {
                    logger.debug("FFMPEG ({}): {}", taskId, line);
                    if (line.trim().startsWith("out_time_ms=")) {
                        long currentTimeInMicro = Long.parseLong(line.trim().substring(12));
                        if (currentTimeInMicro > 0 && totalDurationInMicro > 0) {
                            int progress = (int) ((currentTimeInMicro / totalDurationInMicro) * 100);
                            taskService.updateTaskProgress(taskId, progress,
                                    "正在添加水印: " + progress + "%");
                        }
                    }
                }
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new RuntimeException("FFmpeg 进程以错误码退出: " + exitCode);
            }

            taskService.completeTask(taskId, outputFilename);

        } catch (Exception e) {
            logger.error("水印任务 {} 失败", taskId, e);
            taskService.failTask(taskId, e.getMessage());
        }
    }

    private long calculateBitrate(CompressRequestDTO request) {
        long audioSizeBytes = (long) ((request.getAudioBitrate() / 8.0) * request.getDuration());
        long targetTotalSizeBytes = (long) (request.getTargetSizeMB() * 1024 * 1024);
        long targetVideoSizeBytes = targetTotalSizeBytes - audioSizeBytes;
        if (targetVideoSizeBytes <= 0) {
            throw new IllegalArgumentException("目标大小太小，甚至不足以容纳原始音频！");
        }
        long newVideoBitrate = (long) ((targetVideoSizeBytes * 8) / request.getDuration());
        return newVideoBitrate / 1000;
    }

    public String getOutputDir() {
        return outputDir;
    }
}
