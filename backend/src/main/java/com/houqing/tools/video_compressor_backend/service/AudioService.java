package com.houqing.tools.video_compressor_backend.service;

import com.houqing.tools.video_compressor_backend.dto.AudioExtractRequestDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

@Service
public class AudioService {

    private static final Logger logger = LoggerFactory.getLogger(AudioService.class);

    @Value("${file.upload-dir}")
    private String uploadDir;
    @Value("${file.output-dir}")
    private String outputDir;
    @Value("${ffmpeg.ffmpeg-path}")
    private String ffmpegPath;

    private final TaskService taskService;
    private final ImageService imageService; // 复用 executeProcess

    public AudioService(TaskService taskService, ImageService imageService) {
        this.taskService = taskService;
        this.imageService = imageService;
    }

    @Async
    public void extractAudio(String taskId, AudioExtractRequestDTO request) {
        Path inputFilePath = Paths.get(uploadDir).resolve(request.getUniqueFilename());

        String baseName = request.getUniqueFilename();
        int lastDotIndex = baseName.lastIndexOf('.');
        if (lastDotIndex != -1) {
            baseName = baseName.substring(0, lastDotIndex);
        }
        String outputFilename = "audio_" + baseName + "." + request.getTargetFormat();
        Path outputFilePath = Paths.get(outputDir).resolve(outputFilename);

        try {
            // --- 构建 FFmpeg 命令 ---
            List<String> command = new ArrayList<>();
            command.add(ffmpegPath);
            command.add("-i");
            command.add(inputFilePath.toString());
            command.add("-vn"); // "-vn" 表示 No Video, 禁用视频流

            // 根据目标格式选择不同的音频编码器和质量参数
            switch (request.getTargetFormat().toLowerCase()) {
                case "mp3":
                    command.add("-acodec");
                    command.add("libmp3lame"); // 高质量 MP3 编码器
                    command.add("-q:a");
                    command.add("2"); // VBR 质量, 0(最高)-9(最低), 2 是一个很好的平衡点
                    break;
                case "wav":
                    // WAV 是无损未压缩格式，不需要指定编码器
                    break;
                case "flac":
                    command.add("-acodec");
                    command.add("flac"); // FLAC 无损压缩
                    break;
                case "aac":
                default: // 默认提取为 aac
                    // 如果原始音频就是 aac, 直接复制，速度最快
                    command.add("-acodec");
                    command.add("copy");
                    break;
            }

            command.add("-progress");
            command.add("pipe:1");
            command.add(outputFilePath.toString());

            logger.info("Executing FFmpeg for task {}: {}", taskId, String.join(" ", command));

            // 复用 ImageService 的通用命令行执行方法
            imageService.executeProcess(command, request.getUniqueFilename(), 10); // 允许10分钟超时

            taskService.completeTask(taskId, outputFilename);

        } catch (Exception e) {
            logger.error("!!! 音频提取任务 {} 失败", taskId, e);
            taskService.failTask(taskId, "提取失败: " + e.getMessage());
        }
    }
}