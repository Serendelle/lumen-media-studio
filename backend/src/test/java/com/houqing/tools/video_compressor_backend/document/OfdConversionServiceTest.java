package com.houqing.tools.video_compressor_backend.document;

import com.houqing.tools.video_compressor_backend.common.ApiException;
import com.houqing.tools.video_compressor_backend.common.ErrorCode;
import com.houqing.tools.video_compressor_backend.media.CancellationToken;
import com.houqing.tools.video_compressor_backend.media.MagickPolicy;
import com.houqing.tools.video_compressor_backend.media.ProcessRunner;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.*;

class OfdConversionServiceTest {
    @TempDir Path directory;

    @Test
    void isolatedJarRoundTripPreservesPagesDimensionsAndVisibleContent() throws Exception {
        MagickPolicy policy = mock(MagickPolicy.class);
        when(policy.directory()).thenReturn(directory);
        var service = new OfdConversionService(new ProcessRunner(policy), "");
        assumeTrue(service.available(), "Build backend/ofd-worker before running its integration check");
        Path image = image();
        Path ofd = directory.resolve("测试 页面.ofd");
        service.fromPages(List.of(new OfdConversionService.PageImage(image, 210, 297),
                new OfdConversionService.PageImage(image, 297, 210)), ofd, new CancellationToken());
        assertEquals(2, service.pageCount(ofd));
        Path pdf = service.toPdf(ofd, directory.resolve("pdf"), new CancellationToken());
        try (var document = PdfFacts.open(pdf)) {
            assertEquals(2, document.getNumberOfPages());
            assertEquals(210 * 72 / 25.4, document.getPage(0).getMediaBox().getWidth(), .1);
            assertEquals(297 * 72 / 25.4, document.getPage(1).getMediaBox().getWidth(), .1);
            BufferedImage rendered = new PDFRenderer(document).renderImage(0);
            Color center = new Color(rendered.getRGB(rendered.getWidth() / 2, rendered.getHeight() / 2));
            assertTrue(center.getGreen() > 190 && center.getBlue() < 40 && center.getRed() < 40);
        }
    }

    @Test
    void unavailableWorkerAndCancellationReturnStructuredErrors() throws Exception {
        ProcessRunner runner = mock(ProcessRunner.class);
        var service = new OfdConversionService(runner, directory.resolve("missing.jar").toString());
        assertFalse(service.available());
        Path image = image();
        ApiException unavailable = assertThrows(ApiException.class, () -> service.fromImages(List.of(image), directory.resolve("out.ofd"), new CancellationToken()));
        assertEquals(ErrorCode.CONVERTER_UNAVAILABLE, unavailable.getCode());
        CancellationToken cancelled = new CancellationToken();
        cancelled.cancel();
        ApiException cancellation = assertThrows(ApiException.class, () -> service.fromPages(List.of(new OfdConversionService.PageImage(image, 210, 297)), directory.resolve("cancelled.ofd"), cancelled));
        assertEquals(ErrorCode.CANCELLED, cancellation.getCode());
        verifyNoInteractions(runner);
    }

    private Path image() throws Exception {
        BufferedImage image = new BufferedImage(240, 160, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(new Color(10, 210, 20));
        graphics.fillRect(0, 0, 240, 160);
        graphics.dispose();
        Path file = directory.resolve("测试 图片.png");
        ImageIO.write(image, "png", file.toFile());
        return file;
    }
}
