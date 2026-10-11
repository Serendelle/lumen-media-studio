package com.houqing.tools.video_compressor_backend.service;

import com.houqing.tools.video_compressor_backend.dto.PdfProcessRequestDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.Comparator;

@Service
public class PdfService {

    private static final Logger logger = LoggerFactory.getLogger(PdfService.class);

    @Value("${file.upload-dir}")
    private String uploadDir;
    @Value("${file.output-dir}")
    private String outputDir;
    @Value("${imagemagick.magick-path}")
    private String magickPath;

    private final TaskService taskService;
    private final ImageService imageService; // 复用 ImageService 的执行方法

    public PdfService(TaskService taskService, ImageService imageService) {
        this.taskService = taskService;
        this.imageService = imageService;
    }

    @Async
    public void processPdf(String taskId, PdfProcessRequestDTO request) {
        String tool = request.getTool().toLowerCase();

        try {
            if ("pdf-to-image".equals(tool)) {
                // --- PDF 转图片逻辑 ---
                String inputFile = request.getFilenames().get(0); // 只处理第一个文件
                Path inputFilePath = Paths.get(uploadDir).resolve(inputFile);
                String outputZipFilename = "pdf_to_images_" + taskId + ".zip";
                Path outputZipPath = Paths.get(outputDir).resolve(outputZipFilename);

                // 创建一个临时目录来存放转换出的所有图片
                Path tempImageDir = Paths.get(outputDir).resolve("temp_" + taskId);
                Files.createDirectories(tempImageDir);

                List<String> command = buildPdfToImageCommand(request, inputFilePath, tempImageDir);
                logger.info("Executing PDF->Image for task {}: {}", taskId, String.join(" ", command));
                imageService.executeProcess(command, inputFile, 5); // 允许5分钟超时

                // 将生成的所有图片打包成 ZIP
                try (FileOutputStream fos = new FileOutputStream(outputZipPath.toFile());
                     ZipOutputStream zos = new ZipOutputStream(fos)) {
                    File[] imageFiles = tempImageDir.toFile().listFiles();
                    if (imageFiles != null) {
                        Arrays.sort(imageFiles, Comparator.comparing(File::getName)); // 确保页码顺序
                        for (File imageFile : imageFiles) {
                            ZipEntry zipEntry = new ZipEntry(imageFile.getName());
                            zos.putNextEntry(zipEntry);
                            Files.copy(imageFile.toPath(), zos);
                            zos.closeEntry();
                            Files.delete(imageFile.toPath());
                        }
                    }
                }
                Files.delete(tempImageDir); // 删除临时目录
                taskService.completeTask(taskId, outputZipFilename);

            } else if ("images-to-pdf".equals(tool)) {
                // --- 图片合并为 PDF 逻辑 ---
                List<String> inputFiles = request.getFilenames();
                String outputPdfFilename = "images_to_pdf_" + taskId + ".pdf";
                Path outputPdfPath = Paths.get(outputDir).resolve(outputPdfFilename);

                List<String> command = buildImagesToPdfCommand(request, outputPdfPath);
                logger.info("Executing Images->PDF for task {}: {}", taskId, String.join(" ", command));
                imageService.executeProcess(command, "multiple_files", 5);

                taskService.completeTask(taskId, outputPdfFilename);
            } else {
                throw new IllegalArgumentException("Unknown PDF tool: " + tool);
            }
        } catch (Exception e) {
            logger.error("!!! PDF 处理任务 {} 失败", taskId, e);
            taskService.failTask(taskId, "处理失败: " + e.getMessage());
        }
    }

    private List<String> buildPdfToImageCommand(PdfProcessRequestDTO request, Path inputPath, Path tempOutputDir) {
        List<String> command = new ArrayList<>();
        command.add(magickPath);
        int density = request.getSettings().path("density").asInt(150);
        String format = request.getSettings().path("format").asText("jpg");
        int quality = request.getSettings().path("quality").asInt(90);

        command.add("-density");
        command.add(String.valueOf(density)); // 渲染密度 (DPI)
        command.add(inputPath.toString()); // 输入的 PDF 文件
        command.add("-quality");
        command.add(String.valueOf(quality)); // 图片质量
        // 输出格式 e.g., /path/to/output/page-%d.jpg
        command.add(tempOutputDir.resolve("page-%d." + format).toString());
        return command;
    }

    private List<String> buildImagesToPdfCommand(PdfProcessRequestDTO request, Path outputPath) {
        List<String> command = new ArrayList<>();
        command.add(magickPath);
        // 添加所有输入图片
        for (String filename : request.getFilenames()) {
            command.add(Paths.get(uploadDir).resolve(filename).toString());
        }
        command.add(outputPath.toString()); // 输出的 PDF 文件
        return command;
    }
}