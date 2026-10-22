package com.houqing.tools.video_compressor_backend.document;

import com.houqing.tools.video_compressor_backend.common.ApiException;
import com.houqing.tools.video_compressor_backend.common.ErrorCode;
import com.houqing.tools.video_compressor_backend.media.CancellationToken;
import com.houqing.tools.video_compressor_backend.media.ProcessRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;

/** OFD conversion in a bounded, separate JVM; no OFDRW/PDFBox 2 types enter the application classpath. */
@Service
public class OfdConversionService {
    public record PageImage(Path file, double widthMm, double heightMm) {}
    private final ProcessRunner runner;
    private final String configuredJar;

    public OfdConversionService(ProcessRunner runner, @Value("${app.document.ofd-worker-jar:}") String configuredJar) {
        this.runner = runner;
        this.configuredJar = configuredJar == null ? "" : configuredJar.trim();
    }

    public boolean available() {
        Path jar = workerJar();
        return jar != null && Files.isRegularFile(jar.resolveSibling("lib/ujmp-core-0.3.0.jar")) && Files.isRegularFile(javaExecutable());
    }

    public int pageCount(Path ofd) { return OfdSafety.validate(ofd); }

    /** Rasterizes OFD at 150 DPI (bounded to 16 megapixels per page), retaining its physical page sizes. */
    public Path toPdf(Path ofd, Path outputDir, CancellationToken token) {
        token.throwIfCancelled();
        int expected = OfdSafety.validate(ofd);
        Path work = null;
        Path result = outputDir.resolve("ofd-converted.pdf").toAbsolutePath();
        try {
            Files.createDirectories(outputDir);
            work = Files.createTempDirectory(outputDir, "ofd-worker-").toAbsolutePath();
            run("to-pdf", ofd.toAbsolutePath(), result, work, token);
            try (var pdf = PdfFacts.open(result)) {
                if (pdf.getNumberOfPages() != expected) throw new ApiException(ErrorCode.OUTPUT_INVALID, "OFD 转换后的页数不一致");
            }
            return result;
        } catch (IOException e) {
            deleteFile(result);
            throw new ApiException(ErrorCode.DOCUMENT_CONVERSION_FAILED, "OFD 转换失败，请检查文件内容");
        } catch (RuntimeException e) {
            deleteFile(result);
            throw e;
        } finally { deleteTree(work); }
    }

    /** Images use their pixel size at 150 DPI. PDF callers should use fromPages to retain media box sizes. */
    public void fromImages(List<Path> images, Path output, CancellationToken token) {
        if (images == null || images.isEmpty() || images.size() > OfdSafety.MAX_PAGES) invalidPages();
        List<PageImage> pages = new ArrayList<>();
        for (Path image : images) {
            token.throwIfCancelled();
            try (var stream = ImageIO.createImageInputStream(image.toFile())) {
                if (stream == null) throw new IOException("image");
                var readers = ImageIO.getImageReaders(stream);
                if (!readers.hasNext()) throw new IOException("image");
                var reader = readers.next();
                try {
                    reader.setInput(stream, true, true);
                    int width = reader.getWidth(0), height = reader.getHeight(0);
                    if (width < 1 || height < 1 || (long) width * height > 16_000_000) {
                        throw new ApiException(ErrorCode.VALIDATION_FAILED, "OFD 图片转换每页最多支持 1600 万像素");
                    }
                    pages.add(new PageImage(image, width * 25.4 / 150, height * 25.4 / 150));
                } finally { reader.dispose(); }
            } catch (IOException e) { throw new ApiException(ErrorCode.DOCUMENT_CORRUPTED, "图片无法读取"); }
        }
        fromPages(pages, output, token);
    }

    public void fromPages(List<PageImage> pages, Path output, CancellationToken token) {
        token.throwIfCancelled();
        if (pages == null || pages.isEmpty() || pages.size() > OfdSafety.MAX_PAGES) invalidPages();
        Path work = null;
        Path target = output.toAbsolutePath();
        try {
            Files.createDirectories(target.getParent());
            work = Files.createTempDirectory(target.getParent(), "ofd-worker-").toAbsolutePath();
            Properties properties = new Properties();
            properties.setProperty("count", Integer.toString(pages.size()));
            for (int i = 0; i < pages.size(); i++) {
                PageImage page = pages.get(i);
                if (page == null || page.file() == null || !Files.isRegularFile(page.file())
                        || !Double.isFinite(page.widthMm()) || !Double.isFinite(page.heightMm())
                        || page.widthMm() < 1 || page.heightMm() < 1 || page.widthMm() > 2000 || page.heightMm() > 2000) {
                    throw new ApiException(ErrorCode.VALIDATION_FAILED, "OFD 页面尺寸需在 1—2000 毫米之间");
                }
                properties.setProperty("page." + i + ".file", page.file().toAbsolutePath().toString());
                properties.setProperty("page." + i + ".width", Double.toString(page.widthMm()));
                properties.setProperty("page." + i + ".height", Double.toString(page.heightMm()));
            }
            Path manifest = work.resolve("pages.properties");
            try (var stream = Files.newOutputStream(manifest)) { properties.store(stream, "Lumen OFD page manifest"); }
            run("from-pages", manifest, target, work, token);
            if (OfdSafety.validate(target) != pages.size()) throw new ApiException(ErrorCode.OUTPUT_INVALID, "OFD 输出页数不一致");
        } catch (IOException e) {
            deleteFile(target);
            throw new ApiException(ErrorCode.DOCUMENT_CONVERSION_FAILED, "OFD 转换失败，请检查文件内容");
        } catch (RuntimeException e) {
            deleteFile(target);
            throw e;
        } finally { deleteTree(work); }
    }

    private void run(String operation, Path input, Path output, Path work, CancellationToken token) {
        Path jar = workerJar();
        if (jar == null || !available()) throw new ApiException(ErrorCode.CONVERTER_UNAVAILABLE, "OFD 转换组件未安装，请构建 backend/ofd-worker");
        var command = List.of(javaExecutable().toString(), "-Xmx768m", "-XX:ActiveProcessorCount=2", "-Djava.awt.headless=true",
                "-Dfile.encoding=UTF-8", "-Djava.io.tmpdir=" + work, "-jar", jar.toString(), operation, input.toString(), output.toString());
        try {
            var result = runner.run(command, Duration.ofMinutes(2), token, null, work);
            if (!result.ok()) throw new ApiException(ErrorCode.DOCUMENT_CONVERSION_FAILED, "OFD 转换失败，文件可能包含暂不支持的版式或资源");
            if (!Files.isRegularFile(output) || Files.size(output) == 0 || Files.size(output) > 256L * 1024 * 1024) {
                throw new ApiException(ErrorCode.OUTPUT_INVALID, "OFD 转换未产生有效输出");
            }
        } catch (ApiException e) {
            if (e.getCode() == ErrorCode.PROCESS_TIMEOUT) throw new ApiException(ErrorCode.DOCUMENT_CONVERSION_TIMEOUT, "OFD 转换超时，请减少页数或图片分辨率");
            throw e;
        } catch (IOException e) { throw new ApiException(ErrorCode.OUTPUT_INVALID, "OFD 转换输出无法读取"); }
    }

    private Path workerJar() {
        if (!configuredJar.isEmpty()) {
            Path configured = Path.of(configuredJar).toAbsolutePath().normalize();
            return Files.isRegularFile(configured) ? configured : null;
        }
        for (String candidate : List.of("ofd-worker/target/lumen-ofd-worker.jar", "backend/ofd-worker/target/lumen-ofd-worker.jar", "/opt/lumen/ofd-worker.jar")) {
            Path file = Path.of(candidate).toAbsolutePath().normalize();
            if (Files.isRegularFile(file)) return file;
        }
        return null;
    }

    private static Path javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java");
    }

    private static void invalidPages() { throw new ApiException(ErrorCode.VALIDATION_FAILED, "OFD 转换支持 1—" + OfdSafety.MAX_PAGES + " 页"); }
    private static void deleteFile(Path file) { try { Files.deleteIfExists(file); } catch (IOException ignored) {} }
    private static void deleteTree(Path directory) {
        if (directory == null) return;
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) deleteFile(path);
        } catch (IOException ignored) {}
    }
}
