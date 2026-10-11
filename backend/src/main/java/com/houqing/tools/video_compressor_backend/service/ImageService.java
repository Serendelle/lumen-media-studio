package com.houqing.tools.video_compressor_backend.service;

import com.houqing.tools.video_compressor_backend.dto.ImageProcessRequestDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class ImageService {

    private static final Logger logger = LoggerFactory.getLogger(ImageService.class);

    @Value("${file.upload-dir}")
    private String uploadDir;

    @Value("${file.output-dir}")
    private String outputDir;

    // ImageMagick 路径
    @Value("${imagemagick.magick-path:magick}")
    private String magickPath;

    // 通过 conda 执行 rembg 的配置
    @Value("${ai.conda-path}")
    private String condaPath;          // 例如：C:/Users/you/anaconda3/Scripts/conda.bat

    @Value("${ai.rembg-env-name:rembg-env}")
    private String rembgEnvName;       // 例如：rembg-env

    private final TaskService taskService;

    public ImageService(TaskService taskService) {
        this.taskService = taskService;
    }

    // 保存上传的文件，并返回唯一文件名
    public String saveUploadedFile(MultipartFile file) throws Exception {
        String originalFilename = file.getOriginalFilename();
        String uniqueFilename = UUID.randomUUID().toString() + "_" + originalFilename;
        Path uploadPath = Paths.get(uploadDir);
        if (!Files.exists(uploadPath)) {
            Files.createDirectories(uploadPath);
        }
        Path filePath = uploadPath.resolve(uniqueFilename);
        file.transferTo(filePath.toFile());
        return uniqueFilename;
    }

    /**
     * 图片批量处理（转换/压缩/改尺寸）+ SVG 两步转换
     */
    @Async
    public void processImages(String taskId, ImageProcessRequestDTO request) {
        List<String> inputFiles = request.getFilenames();
        int totalFiles = inputFiles.size();
        String outputZipFilename = "processed_" + taskId + ".zip";
        Path outputZipPath = Paths.get(outputDir).resolve(outputZipFilename);

        try (FileOutputStream fos = new FileOutputStream(outputZipPath.toFile());
             ZipOutputStream zos = new ZipOutputStream(fos)) {

            for (int i = 0; i < totalFiles; i++) {
                String inputFile = inputFiles.get(i);
                Path inputFilePath = Paths.get(uploadDir).resolve(inputFile);

                // 打包进 zip 的最终名字
                String zipEntryName = generateOutputFilename(inputFile, request);

                // 实际磁盘上的临时输出文件
                String tempOutputFilename = UUID.randomUUID().toString() + "_" + zipEntryName;
                Path finalOutputFilePath = Paths.get(outputDir).resolve(tempOutputFilename);

                boolean isSvgToPng =
                        inputFilePath.toString().toLowerCase().endsWith(".svg")
                                && "convert".equalsIgnoreCase(request.getTool())
                                && "png".equalsIgnoreCase(request.getSettings().path("targetFormat").asText(""));

                if (isSvgToPng) {
                    // STEP 1: SVG -> 中间 PNG
                    Path intermediatePngPath = Paths.get(outputDir).resolve("intermediate_" + tempOutputFilename);
                    List<String> commandStep1 = buildMagickCommand(request, inputFilePath, intermediatePngPath);
                    logger.info("Executing SVG->PNG (Step 1) for file {}: {}", inputFile, String.join(" ", commandStep1));
                    executeProcess(commandStep1, inputFile, 2);

                    // STEP 2: 中间 PNG -> 干净 PNG32
                    List<String> commandStep2 = new ArrayList<>();
                    commandStep2.add(magickPath);
                    commandStep2.add(intermediatePngPath.toString());
                    commandStep2.add("-strip");
                    commandStep2.add("PNG32:" + finalOutputFilePath.toString());
                    logger.info("Executing PNG->PNG (Step 2 - purification) for file {}: {}", inputFile, String.join(" ", commandStep2));
                    executeProcess(commandStep2, inputFile, 2);

                    Files.deleteIfExists(intermediatePngPath);

                } else {
                    // 普通的一步处理
                    List<String> command = buildMagickCommand(request, inputFilePath, finalOutputFilePath);
                    logger.info("Executing ImageMagick for file {}: {}", inputFile, String.join(" ", command));
                    executeProcess(command, inputFile, 2);
                }

                // 写入 zip
                ZipEntry zipEntry = new ZipEntry(zipEntryName);
                zos.putNextEntry(zipEntry);
                Files.copy(finalOutputFilePath, zos);
                zos.closeEntry();

                // 删除临时输出
                Files.deleteIfExists(finalOutputFilePath);

                // 更新进度
                int progress = (int) (((i + 1.0) / totalFiles) * 100);
                taskService.updateTaskProgress(taskId, progress,
                        String.format("正在处理第 %d / %d 个文件...", i + 1, totalFiles));
            }

            taskService.completeTask(taskId, outputZipFilename);

        } catch (Exception e) {
            logger.error("!!! 图片处理任务 {} 失败", taskId, e);
            taskService.failTask(taskId, "处理失败: " + e.getMessage());
        }
    }

    /**
     * 新增：通过 conda run 调 rembg 的去背景任务
     */
    @Async
    public void removeBackground(String taskId, String uniqueFilename) {
        Path inputFilePath = Paths.get(uploadDir).resolve(uniqueFilename);

        // "uuid_cat.jpg" -> "cat_bg_removed.png"
        String baseName = uniqueFilename.substring(uniqueFilename.indexOf('_') + 1);
        String nameWithoutExt = baseName.substring(0, baseName.lastIndexOf('.'));
        String outputFilename = nameWithoutExt + "_bg_removed.png";
        Path outputFilePath = Paths.get(outputDir).resolve(outputFilename);

        try {
            taskService.updateTaskProgress(taskId, 10, "正在加载 AI 模型...");

            // 构建：conda.bat run -n rembg-env --no-capture-output rembg i in out
            List<String> command = new ArrayList<>();
            command.add(condaPath);
            command.add("run");
            command.add("-n");
            command.add(rembgEnvName);
            command.add("--no-capture-output");
            command.add("rembg");
            command.add("i");
            command.add(inputFilePath.toString());
            command.add(outputFilePath.toString());

            logger.info("Executing Rembg via Conda for task {}: {}", taskId, String.join(" ", command));

            // rembg 第一次拉模型可能慢一点，给 3 分钟
            executeProcess(command, uniqueFilename, 3);

            taskService.updateTaskProgress(taskId, 90, "AI 处理完成，正在保存文件...");
            taskService.completeTask(taskId, outputFilename);

        } catch (Exception e) {
            logger.error("!!! 背景去除任务 {} 失败", taskId, e);
            taskService.failTask(taskId, "AI 处理失败: " + e.getMessage());
        }
    }

    /**
     * 通用外部进程执行
     */
    public void executeProcess(List<String> command, String inputFile, int timeoutMinutes) throws Exception {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);
        Process process = processBuilder.start();

        StringBuilder cmdOutput = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                cmdOutput.append(line).append("\n");
            }
        }

        boolean finished = process.waitFor(timeoutMinutes, TimeUnit.MINUTES);
        if (!finished) {
            process.destroy();
            throw new RuntimeException("Process timed out for file " + inputFile);
        }

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            logger.error("Process failed for file {}. Output: {}", inputFile, cmdOutput);
            throw new RuntimeException("Process failed with exit code " + exitCode + " for file " + inputFile);
        }
    }

    /**
     * 构建 ImageMagick 命令（带 SVG 渲染 + PNG32）
     */
    private List<String> buildMagickCommand(ImageProcessRequestDTO request, Path inputPath, Path outputPath) {
        List<String> command = new ArrayList<>();
        command.add(magickPath);

        String inputPathStrLower = inputPath.toString().toLowerCase();

        // SVG 特殊处理
        if (inputPathStrLower.endsWith(".svg")) {
            command.add("-density");
            command.add("300");
            command.add("-background");
            command.add("none");
        }

        // 输入文件
        command.add(inputPath.toString());

        switch (request.getTool().toLowerCase()) {
            case "resize":
                int width = request.getSettings().path("width").asInt(0);
                int height = request.getSettings().path("height").asInt(0);
                String resizeValue = "";
                if (width > 0 && height > 0) {
                    resizeValue = width + "x" + height + "!";
                } else if (width > 0) {
                    resizeValue = width + "x";
                } else if (height > 0) {
                    resizeValue = "x" + height;
                }
                if (!resizeValue.isEmpty()) {
                    command.add("-resize");
                    command.add(resizeValue);
                }
                break;

            case "compress":
                int quality = request.getSettings().path("quality").asInt(80);
                command.add("-quality");
                command.add(String.valueOf(quality));
                break;

            case "convert":
                // 转格式这里不需要额外参数
                break;
        }

        // 去掉多余元数据
        command.add("-strip");

        // PNG 强制 PNG32
        String outputPathStr = outputPath.toString();
        if (outputPathStr.toLowerCase().endsWith(".png")) {
            command.add("PNG32:" + outputPathStr);
        } else {
            command.add(outputPathStr);
        }

        return command;
    }

    // 生成打包进 zip 的文件名
    private String generateOutputFilename(String originalUniqueName, ImageProcessRequestDTO request) {
        String baseName = originalUniqueName.substring(originalUniqueName.indexOf('_') + 1);
        String nameWithoutExt = baseName.substring(0, baseName.lastIndexOf('.'));

        if ("convert".equalsIgnoreCase(request.getTool())) {
            String targetFormat = request.getSettings().get("targetFormat").asText("jpg");
            return nameWithoutExt + "." + targetFormat;
        }

        return baseName;
    }
}
